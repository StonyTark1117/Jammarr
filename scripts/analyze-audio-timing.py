#!/usr/bin/env python3
"""Dependency-free timing analysis for stereo s16le Jammarr captures."""

import argparse
import array
import json
import math
import sys
from pathlib import Path


def goertzel(samples: list[float], sample_rate: int, frequency: float) -> float:
    coefficient = 2.0 * math.cos(2.0 * math.pi * frequency / sample_rate)
    previous = 0.0
    previous_two = 0.0
    for sample in samples:
        value = sample + coefficient * previous - previous_two
        previous_two = previous
        previous = value
    return previous_two * previous_two + previous * previous - coefficient * previous * previous_two


def read_mono(path: Path) -> list[float]:
    values = array.array("h")
    values.frombytes(path.read_bytes())
    if sys.byteorder != "little":
        values.byteswap()
    return [float(values[index]) for index in range(0, len(values) - 1, 2)]


def analyze(path: Path, sample_rate: int) -> dict:
    samples = read_mono(path)
    window = max(1, sample_rate // 100)
    labels: list[int] = []
    silent: list[bool] = []
    for offset in range(0, len(samples) - window + 1, window):
        frame = samples[offset:offset + window]
        rms = math.sqrt(sum(value * value for value in frame) / len(frame))
        silent.append(rms < 350.0)
        carrier = goertzel(frame, sample_rate, 997.0)
        low = goertzel(frame, sample_rate, 1477.0)
        high = goertzel(frame, sample_rate, 1975.0)
        strongest = max(low, high)
        # LWJGL 2 / Paulscode attenuates the lower marker relative to the
        # always-present carrier.  Dominance over the other marker frequency
        # keeps this selective while allowing the 1.7.10 capture path to use
        # the same deterministic fixture as modern OpenAL clients.
        if strongest > carrier * 0.40 and strongest > min(low, high) * 2.0:
            labels.append(0 if low > high else 1)
        else:
            labels.append(-1)

    runs: list[tuple[int, int, int]] = []
    index = 0
    while index < len(labels):
        label = labels[index]
        if label < 0:
            index += 1
            continue
        start = index
        while index + 1 < len(labels) and labels[index + 1] == label:
            index += 1
        if index - start + 1 >= 3:
            runs.append((label, start * 10, (index - start + 1) * 10))
        index += 1

    first_marker_window = runs[0][1] // 10 if runs else len(silent)
    longest_silence = 0
    current_silence = 0
    for value in silent[first_marker_window:]:
        current_silence = current_silence + 10 if value else 0
        longest_silence = max(longest_silence, current_silence)
    interval_start = 2 if runs and (runs[0][1] <= 20 or runs[0][2] < 60) else 1
    intervals = [runs[index][1] - runs[index - 1][1]
                 for index in range(interval_start, len(runs))]
    marker_error = max([abs(value - 250) for value in intervals] or [10**9])
    return {
        "file": str(path),
        "duration_ms": round(len(samples) * 1000.0 / sample_rate),
        "first_marker_ms": runs[0][1] if runs else None,
        "marker_count": len(runs),
        "max_marker_interval_error_ms": marker_error,
        "max_silence_ms": longest_silence,
        "markers": [{"type": label, "time_ms": time, "duration_ms": duration}
                    for label, time, duration in runs],
    }


def skew(first: dict, second: dict) -> int | None:
    left = first["markers"]
    right = second["markers"]
    best = None
    for a in left[:4]:
        for b in right[:4]:
            if a["type"] != b["type"]:
                continue
            candidate = abs(a["time_ms"] - b["time_ms"])
            best = candidate if best is None else min(best, candidate)
    return best


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("capture", type=Path)
    parser.add_argument("--reference", type=Path)
    parser.add_argument("--sample-rate", type=int, default=48000)
    parser.add_argument("--minimum-duration-ms", type=int, default=10000)
    # Markers repeat every 250 ms, so an asynchronously started capture can
    # legitimately wait one full period plus a 10 ms analysis window.
    parser.add_argument("--maximum-onset-ms", type=int, default=300)
    parser.add_argument("--maximum-silence-ms", type=int, default=60)
    parser.add_argument("--maximum-marker-error-ms", type=int, default=40)
    parser.add_argument("--maximum-skew-ms", type=int, default=150)
    args = parser.parse_args()

    report = {"capture": analyze(args.capture, args.sample_rate)}
    failures: list[str] = []
    capture = report["capture"]
    if capture["duration_ms"] < args.minimum_duration_ms:
        failures.append("capture is shorter than the required duration")
    if capture["marker_count"] < max(4, args.minimum_duration_ms // 350):
        failures.append("too few timing markers were detected")
    if capture["first_marker_ms"] is None or capture["first_marker_ms"] > args.maximum_onset_ms:
        failures.append("marker onset exceeded the threshold")
    if capture["max_silence_ms"] > args.maximum_silence_ms:
        failures.append("a post-start silence gap exceeded the threshold")
    if capture["max_marker_interval_error_ms"] > args.maximum_marker_error_ms:
        failures.append("marker displacement exceeded the threshold")

    if args.reference is not None:
        report["reference"] = analyze(args.reference, args.sample_rate)
        report["inter_client_skew_ms"] = skew(capture, report["reference"])
        if report["inter_client_skew_ms"] is None or report["inter_client_skew_ms"] > args.maximum_skew_ms:
            failures.append("inter-client skew exceeded the threshold")
    report["failures"] = failures
    print(json.dumps(report, indent=2, sort_keys=True))
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()

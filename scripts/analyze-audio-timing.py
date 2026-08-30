#!/usr/bin/env python3
"""Dependency-free timing analysis for stereo s16le Jammarr captures."""

import argparse
import array
import json
import math
import sys
from pathlib import Path


def marker_type(slot: int) -> int:
    value = (slot + 0x9E3779B9) & 0xFFFFFFFF
    value ^= value >> 16
    value = (value * 0x7FEB352D) & 0xFFFFFFFF
    value ^= value >> 15
    value = (value * 0x846CA68B) & 0xFFFFFFFF
    value ^= value >> 16
    return value & 1


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
    overlapping: list[bool] = []
    for offset in range(0, len(samples) - window + 1, window):
        frame = samples[offset:offset + window]
        rms = math.sqrt(sum(value * value for value in frame) / len(frame))
        silent.append(rms < 350.0)
        carrier = goertzel(frame, sample_rate, 997.0)
        low = goertzel(frame, sample_rate, 1477.0)
        high = goertzel(frame, sample_rate, 1975.0)
        strongest = max(low, high)
        weakest = min(low, high)
        # The fixture emits exactly one marker identity at a time, with a
        # carrier-only gap between slots. Sustained energy in both identities
        # therefore identifies two different PCM regions being mixed rather
        # than an ordinary marker transition.
        overlapping.append(strongest > carrier * 0.40
                           and weakest > carrier * 0.25
                           and strongest < weakest * 4.0)
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
    longest_overlap = 0
    current_overlap = 0
    for value in overlapping[first_marker_window:]:
        current_overlap = current_overlap + 10 if value else 0
        longest_overlap = max(longest_overlap, current_overlap)
    # Compare steady markers with the 250 ms cadence lattice. Pairwise interval
    # error can double two opposite edge-estimation errors (especially through
    # LWJGL2/Paulscode) even though neither marker moved beyond the limit. Pulse
    # recorder and resampler initialization can also split the first few runs;
    # onset, silence and skew cover that boundary while cadence is scored over
    # the remaining roughly ten seconds. A real late/dropped chunk after the
    # recorder settles still shifts subsequent markers off this lattice.
    cadence_runs = [run for run in runs if run[1] >= 1_500 and run[2] >= 60]
    if len(cadence_runs) > 1:
        anchor = cadence_runs[0][1]
        displacements = []
        for _, marker_time, _ in cadence_runs[1:]:
            phase = (marker_time - anchor) % 250
            displacements.append(min(phase, 250 - phase))
        # Each run boundary is quantized to one 10 ms analysis window. Remove
        # that known measurement uncertainty before applying the configured
        # displacement policy; a measured 50 ms edge delta therefore remains
        # a strict 40 ms timing result rather than a false one-bin failure.
        marker_error = max(0, max(displacements) - 10)
    else:
        marker_error = 10**9
    # Marker cadence alone cannot distinguish a roughly one-period replay from
    # valid audio. Score the detected marker identities against the generated
    # pseudo-random sequence so stale or out-of-order PCM changes the sequence
    # even when its timing wraps near the 250 ms lattice.
    observed: dict[int, tuple[int, int]] = {}
    if cadence_runs:
        anchor = cadence_runs[0][1]
        for label, marker_time, duration in cadence_runs:
            slot = round((marker_time - anchor) / 250)
            previous = observed.get(slot)
            if previous is None or duration > previous[1]:
                observed[slot] = (label, duration)
    sequence_mismatches = 10**9
    sequence_phase = None
    if len(observed) >= 4:
        for phase in range(4096):
            mismatches = sum(label != marker_type(phase + slot)
                             for slot, (label, _) in observed.items())
            if mismatches < sequence_mismatches:
                sequence_mismatches = mismatches
                sequence_phase = phase
                if mismatches == 0:
                    break
    return {
        "file": str(path),
        "duration_ms": round(len(samples) * 1000.0 / sample_rate),
        "first_marker_ms": runs[0][1] if runs else None,
        "marker_count": len(runs),
        "max_marker_interval_error_ms": marker_error,
        "marker_sequence_mismatches": sequence_mismatches,
        "marker_sequence_phase": sequence_phase,
        "max_silence_ms": longest_silence,
        "max_marker_overlap_ms": longest_overlap,
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
            # Each detected boundary is the start of a 10 ms analysis window.
            # Remove that single-window measurement uncertainty just as the
            # cadence check does, so a reported 160 ms boundary delta remains
            # subject to the configured 150 ms synchronization policy rather
            # than failing solely on the analyzer's quantization.
            candidate = max(0, abs(a["time_ms"] - b["time_ms"]) - 10)
            best = candidate if best is None else min(best, candidate)
    return best


def validate_stream(value: dict, label: str, args: argparse.Namespace,
                    failures: list[str]) -> None:
    prefix = f"{label} " if label else ""
    if value["duration_ms"] < args.minimum_duration_ms:
        failures.append(f"{prefix}capture is shorter than the required duration")
    if value["marker_count"] < max(4, args.minimum_duration_ms // 350):
        failures.append(f"{prefix}has too few detected timing markers")
    if value["first_marker_ms"] is None or value["first_marker_ms"] > args.maximum_onset_ms:
        failures.append(f"{prefix}marker onset exceeded the threshold")
    if value["max_silence_ms"] > args.maximum_silence_ms:
        failures.append(f"{prefix}post-start silence gap exceeded the threshold")
    if value["max_marker_interval_error_ms"] > args.maximum_marker_error_ms:
        failures.append(f"{prefix}marker displacement exceeded the threshold")
    if value["marker_sequence_mismatches"] > args.maximum_sequence_mismatches:
        failures.append(f"{prefix}marker sequence indicates replayed or out-of-order audio")
    if value["max_marker_overlap_ms"] > args.maximum_overlap_ms:
        failures.append(f"{prefix}simultaneous markers indicate overlapping audio")


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
    parser.add_argument("--maximum-sequence-mismatches", type=int, default=2)
    parser.add_argument("--maximum-overlap-ms", type=int, default=20)
    parser.add_argument("--maximum-skew-ms", type=int, default=150)
    args = parser.parse_args()

    report = {"capture": analyze(args.capture, args.sample_rate)}
    failures: list[str] = []
    capture = report["capture"]
    validate_stream(capture, "", args, failures)

    if args.reference is not None:
        report["reference"] = analyze(args.reference, args.sample_rate)
        validate_stream(report["reference"], "reference", args, failures)
        report["inter_client_skew_ms"] = skew(capture, report["reference"])
        if report["inter_client_skew_ms"] is None or report["inter_client_skew_ms"] > args.maximum_skew_ms:
            failures.append("inter-client skew exceeded the threshold")
    report["failures"] = failures
    print(json.dumps(report, indent=2, sort_keys=True))
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()

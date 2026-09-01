#!/usr/bin/env python3
"""Measure arbitrary two-client PCM for silence, skew, skips, and reordering.

Unlike analyze-audio-timing.py, this analyzer does not assume the synthetic
997 Hz fixture. It aligns two captures of the same real program material,
then scores their common waveform in short blocks. A client-specific skipped,
late, replayed, or reordered region produces a run of low-correlation blocks.
Pre-backend decoder traces can be supplied separately to distinguish transport
or decode divergence from the rendered OpenAL/Pulse path.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from scipy import signal


def read_pcm(path: Path, channels: int) -> np.ndarray:
    raw = np.fromfile(path, dtype="<i2")
    if raw.size < channels:
        return np.empty(0, dtype=np.float64)
    raw = raw[: raw.size - raw.size % channels].reshape(-1, channels)
    mono = raw.astype(np.float64).mean(axis=1)
    mono -= mono.mean() if mono.size else 0.0
    return mono


def decimate(value: np.ndarray, factor: int) -> np.ndarray:
    if factor <= 1:
        return value
    usable = value.size - value.size % factor
    if usable <= 0:
        return np.empty(0, dtype=np.float64)
    return value[:usable].reshape(-1, factor).mean(axis=1)


def normalized_correlation(left: np.ndarray, right: np.ndarray) -> float:
    if left.size == 0 or right.size == 0 or left.size != right.size:
        return 0.0
    denominator = float(np.linalg.norm(left) * np.linalg.norm(right))
    return 0.0 if denominator == 0.0 else float(np.dot(left, right) / denominator)


def best_lag(left: np.ndarray, right: np.ndarray, maximum_lag: int, factor: int) -> int:
    left_small = decimate(left, factor)
    right_small = decimate(right, factor)
    if left_small.size == 0 or right_small.size == 0:
        return 0
    correlation = signal.correlate(left_small, right_small, mode="full", method="fft")
    lags = signal.correlation_lags(left_small.size, right_small.size, mode="full")
    bounded = np.abs(lags) <= max(1, maximum_lag // factor)
    if not np.any(bounded):
        return 0
    bounded_indexes = np.flatnonzero(bounded)
    index = bounded_indexes[int(np.argmax(np.abs(correlation[bounded])))]
    coarse_lag = int(lags[index] * factor)

    # Decimation makes the FFT search inexpensive for long real-music traces,
    # but its result is quantized to ``factor`` samples. Even a two-sample
    # error can make otherwise identical full-rate PCM look divergent. Refine
    # the winning coarse cell plus its immediate neighbours against the native
    # samples before computing block-level continuity metrics.
    refinement = max(0, factor)
    first = max(-maximum_lag, coarse_lag - refinement)
    last = min(maximum_lag, coarse_lag + refinement)
    best_score = -1.0
    refined_lag = coarse_lag
    for candidate in range(first, last + 1):
        left_common, right_common = aligned(left, right, candidate)
        score = abs(normalized_correlation(left_common, right_common))
        if score > best_score:
            best_score = score
            refined_lag = candidate
    return refined_lag


def aligned(left: np.ndarray, right: np.ndarray, lag: int) -> tuple[np.ndarray, np.ndarray]:
    if lag >= 0:
        left_start, right_start = lag, 0
    else:
        left_start, right_start = 0, -lag
    common = min(left.size - left_start, right.size - right_start)
    if common <= 0:
        return np.empty(0), np.empty(0)
    return left[left_start : left_start + common], right[right_start : right_start + common]


def longest_silence_ms(value: np.ndarray, sample_rate: int, block_ms: int = 20) -> int:
    block = max(1, sample_rate * block_ms // 1000)
    usable = value.size - value.size % block
    if usable <= 0:
        return 0
    rms = np.sqrt(np.mean(value[:usable].reshape(-1, block) ** 2, axis=1))
    active = rms[rms > 0]
    if active.size == 0:
        return value.size * 1000 // sample_rate
    threshold = max(24.0, float(np.percentile(active, 75)) * 0.015)
    longest = current = 0
    began = False
    for level in rms:
        if level > threshold:
            began = True
            current = 0
        elif began:
            current += block_ms
            longest = max(longest, current)
    return longest


def analyze_pair(
    left: np.ndarray,
    right: np.ndarray,
    sample_rate: int,
    maximum_lag_ms: int,
    block_ms: int,
) -> dict[str, object]:
    maximum_lag = sample_rate * maximum_lag_ms // 1000
    factor = max(1, sample_rate // 4000)
    lag = best_lag(left, right, maximum_lag, factor)
    left_common, right_common = aligned(left, right, lag)
    block = max(1, sample_rate * block_ms // 1000)
    usable = min(left_common.size, right_common.size)
    usable -= usable % block
    left_common = left_common[:usable]
    right_common = right_common[:usable]
    correlations: list[float] = []
    bad_run = current_bad_run = 0
    for start in range(0, usable, block):
        score = normalized_correlation(
            left_common[start : start + block], right_common[start : start + block]
        )
        correlations.append(score)
        if score < 0.80:
            current_bad_run += 1
            bad_run = max(bad_run, current_bad_run)
        else:
            current_bad_run = 0
    return {
        "duration_ms": usable * 1000 // sample_rate,
        "lag_ms": round(lag * 1000.0 / sample_rate, 3),
        "correlation": round(normalized_correlation(left_common, right_common), 6),
        "median_block_correlation": round(float(np.median(correlations)), 6)
        if correlations
        else 0.0,
        "minimum_block_correlation": round(float(np.min(correlations)), 6)
        if correlations
        else 0.0,
        "bad_block_count": sum(score < 0.80 for score in correlations),
        "longest_bad_run_ms": bad_run * block_ms,
        "left_longest_silence_ms": longest_silence_ms(left_common, sample_rate),
        "right_longest_silence_ms": longest_silence_ms(right_common, sample_rate),
    }


def validate(name: str, report: dict[str, object], args: argparse.Namespace) -> list[str]:
    failures: list[str] = []
    if report["duration_ms"] < args.minimum_duration_ms:
        failures.append(f"{name} common duration is shorter than required")
    if abs(float(report["lag_ms"])) > args.maximum_skew_ms:
        failures.append(f"{name} inter-client skew exceeded the threshold")
    if float(report["correlation"]) < args.minimum_correlation:
        failures.append(f"{name} whole-stream correlation fell below the threshold")
    if float(report["median_block_correlation"]) < args.minimum_block_correlation:
        failures.append(f"{name} median block correlation fell below the threshold")
    if int(report["longest_bad_run_ms"]) > args.maximum_bad_run_ms:
        failures.append(f"{name} contains a sustained divergent/skipped/reordered region")
    if max(
        int(report["left_longest_silence_ms"]), int(report["right_longest_silence_ms"])
    ) > args.maximum_silence_ms:
        failures.append(f"{name} contains an excessive post-start silence gap")
    return failures


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("left", type=Path)
    parser.add_argument("right", type=Path)
    parser.add_argument("--sample-rate", type=int, default=48000)
    parser.add_argument("--channels", type=int, default=2)
    parser.add_argument("--trace-left", type=Path)
    parser.add_argument("--trace-right", type=Path)
    parser.add_argument("--trace-sample-rate", type=int, default=44100)
    parser.add_argument("--minimum-duration-ms", type=int, default=10000)
    parser.add_argument("--maximum-skew-ms", type=int, default=250)
    parser.add_argument("--maximum-search-lag-ms", type=int, default=60000)
    parser.add_argument("--minimum-correlation", type=float, default=0.85)
    parser.add_argument("--minimum-block-correlation", type=float, default=0.90)
    parser.add_argument("--maximum-bad-run-ms", type=int, default=300)
    parser.add_argument("--maximum-silence-ms", type=int, default=1000)
    parser.add_argument("--block-ms", type=int, default=100)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    rendered = analyze_pair(
        read_pcm(args.left, args.channels),
        read_pcm(args.right, args.channels),
        args.sample_rate,
        args.maximum_skew_ms,
        args.block_ms,
    )
    report: dict[str, object] = {"rendered": rendered}
    failures = validate("rendered", rendered, args)
    if bool(args.trace_left) != bool(args.trace_right):
        failures.append("both decoder traces are required together")
    elif args.trace_left and args.trace_right:
        traces = analyze_pair(
            read_pcm(args.trace_left, args.channels),
            read_pcm(args.trace_right, args.channels),
            args.trace_sample_rate,
            args.maximum_search_lag_ms,
            args.block_ms,
        )
        report["decoder_traces"] = traces
        trace_args = argparse.Namespace(**vars(args))
        trace_args.maximum_skew_ms = args.maximum_search_lag_ms
        trace_args.minimum_correlation = max(args.minimum_correlation, 0.98)
        trace_args.minimum_block_correlation = max(args.minimum_block_correlation, 0.98)
        failures.extend(validate("decoder traces", traces, trace_args))
    report["failures"] = failures
    report["passed"] = not failures
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())

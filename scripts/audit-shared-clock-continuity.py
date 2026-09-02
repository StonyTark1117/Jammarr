#!/usr/bin/env python3
"""Audit inter-cycle phase continuity in shared-clock marked-audio captures."""

from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path
import re
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
TIMING_ANALYZER = SCRIPT_DIR / "analyze-audio-timing.py"
SPEC = importlib.util.spec_from_file_location("jammarr_audio_timing", TIMING_ANALYZER)
assert SPEC and SPEC.loader
timing = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(timing)


def marker_labels(path: Path, sample_rate: int) -> list[int]:
    samples = timing.read_mono(path)
    window = max(1, sample_rate // 100)
    labels: list[int] = []
    for offset in range(0, len(samples) - window + 1, window):
        frame = samples[offset : offset + window]
        carrier = timing.goertzel(frame, sample_rate, 997.0)
        low = timing.goertzel(frame, sample_rate, 1477.0)
        high = timing.goertzel(frame, sample_rate, 1975.0)
        strongest = max(low, high)
        if strongest > carrier * 0.40 and strongest > min(low, high) * 2.0:
            labels.append(0 if low > high else 1)
        else:
            labels.append(-1)
    return labels


def aligned_labels(
    left: list[int], right: list[int], lag: int
) -> tuple[list[int], list[int]]:
    if lag >= 0:
        left = left[lag:]
    else:
        right = right[-lag:]
    common = min(len(left), len(right))
    return left[:common], right[:common]


def best_marker_lag(
    left: list[int], right: list[int], maximum_lag_bins: int
) -> dict[str, int | float]:
    best: tuple[tuple[float, int, int], int, float, int] | None = None
    for lag in range(-maximum_lag_bins, maximum_lag_bins + 1):
        aligned_left, aligned_right = aligned_labels(left, right, lag)
        active_pairs = [
            (left_label, right_label)
            for left_label, right_label in zip(aligned_left, aligned_right)
            if left_label != -1 or right_label != -1
        ]
        if not active_pairs:
            continue
        score = sum(left_label == right_label for left_label, right_label in active_pairs) / len(
            active_pairs
        )
        key = (score, len(active_pairs), -abs(lag))
        if best is None or key > best[0]:
            best = (key, lag, score, len(active_pairs))
    if best is None:
        return {"lagBins": 0, "score": 0.0, "activeWindows": 0}
    return {
        "lagBins": best[1],
        "score": round(best[2], 6),
        "activeWindows": best[3],
    }


def capture_cycles(root: Path, prefix: str) -> list[tuple[int, Path, Path]]:
    pattern = re.compile(rf"^{re.escape(prefix)}-([0-9]{{5}})\.classification\.json$")
    cycles: list[tuple[int, Path, Path]] = []
    for classification in root.glob(f"{prefix}-*.classification.json"):
        match = pattern.fullmatch(classification.name)
        if match is None:
            continue
        cycle = int(match.group(1))
        base = root / f"{prefix}-{cycle:05d}"
        # Prefixes contain Minecraft version dots, so Path.with_suffix() would
        # replace most of the generated basename instead of appending a role.
        leader = Path(f"{base}.leader.s16le")
        follower = Path(f"{base}.follower.s16le")
        if leader.is_file() and follower.is_file():
            cycles.append((cycle, leader, follower))
    return sorted(cycles)


def analyze_pair(
    left: Path,
    right: Path,
    sample_rate: int,
    maximum_search_lag_ms: int,
) -> dict[str, int | float]:
    report = best_marker_lag(
        marker_labels(left, sample_rate),
        marker_labels(right, sample_rate),
        max(1, maximum_search_lag_ms // 10),
    )
    report["lagMs"] = int(report.pop("lagBins")) * 10
    return report


def audit(args: argparse.Namespace) -> dict[str, Any]:
    cycles = capture_cycles(args.root, args.prefix)
    reports: list[dict[str, Any]] = []
    failures: list[str] = []
    previous_cycle: int | None = None
    previous_lag: int | None = None
    for cycle, leader, follower in cycles:
        pair = analyze_pair(
            leader, follower, args.sample_rate, args.maximum_search_lag_ms
        )
        lag = int(pair["lagMs"])
        row: dict[str, Any] = {"cycle": cycle, **pair}
        if float(pair["score"]) < args.minimum_score:
            failures.append(f"cycle {cycle} marker-envelope correlation is ambiguous")
        if abs(lag) > args.maximum_skew_ms:
            failures.append(f"cycle {cycle} inter-client skew exceeded the threshold")
        if previous_cycle is not None:
            if cycle != previous_cycle + 1:
                failures.append(
                    f"cycle sequence is not contiguous between {previous_cycle} and {cycle}"
                )
            assert previous_lag is not None
            step = lag - previous_lag
            row["lagStepMs"] = step
            if abs(step) > args.maximum_step_ms:
                failures.append(
                    f"cycle {cycle} inter-client phase changed by {step} ms"
                )
        reports.append(row)
        previous_cycle = cycle
        previous_lag = lag

    if len(reports) < args.minimum_cycles:
        failures.append(
            f"found {len(reports)} complete cycles; require at least {args.minimum_cycles}"
        )

    feed_report: dict[str, Any] | None = None
    feed_healthy = False
    if args.leader_feed is not None or args.follower_feed is not None:
        if args.leader_feed is None or args.follower_feed is None:
            failures.append("both leader and follower feed paths are required")
        elif not args.leader_feed.is_file() or not args.follower_feed.is_file():
            failures.append("one or both feed paths are missing")
        else:
            feed_report = analyze_pair(
                args.leader_feed,
                args.follower_feed,
                args.feed_sample_rate,
                args.maximum_search_lag_ms,
            )
            feed_healthy = (
                float(feed_report["score"]) >= args.minimum_score
                and abs(int(feed_report["lagMs"])) <= args.maximum_step_ms
            )

    phase_failure = any("phase changed" in failure for failure in failures)
    if phase_failure and feed_healthy:
        classification = "rendered-backend-phase-shift"
    elif failures:
        classification = "upstream-or-indeterminate-continuity-failure"
    else:
        classification = "pass"
    return {
        "schemaVersion": 1,
        "classification": classification,
        "passed": not failures,
        "sampleRate": args.sample_rate,
        "maximumSkewMs": args.maximum_skew_ms,
        "maximumStepMs": args.maximum_step_ms,
        "minimumScore": args.minimum_score,
        "cycles": reports,
        "feed": feed_report,
        "failures": failures,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--prefix", required=True)
    parser.add_argument("--sample-rate", type=int, default=48_000)
    parser.add_argument("--minimum-cycles", type=int, default=2)
    parser.add_argument("--maximum-search-lag-ms", type=int, default=500)
    parser.add_argument("--maximum-skew-ms", type=int, default=150)
    parser.add_argument("--maximum-step-ms", type=int, default=40)
    parser.add_argument("--minimum-score", type=float, default=0.90)
    parser.add_argument("--leader-feed", type=Path)
    parser.add_argument("--follower-feed", type=Path)
    parser.add_argument("--feed-sample-rate", type=int, default=44_100)
    args = parser.parse_args()
    if args.sample_rate <= 0 or args.feed_sample_rate <= 0:
        parser.error("sample rates must be positive")
    if args.minimum_cycles < 2:
        parser.error("--minimum-cycles must be at least 2")
    if args.maximum_search_lag_ms < args.maximum_skew_ms:
        parser.error("search lag must cover the skew threshold")
    if not 0.0 <= args.minimum_score <= 1.0:
        parser.error("--minimum-score must be from 0 through 1")
    return args


def main() -> int:
    report = audit(parse_args())
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

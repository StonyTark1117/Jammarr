#!/usr/bin/env python3
"""Classify a dual-client timing failure captured on one audio graph clock."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


STREAM_FAILURE_SUFFIXES = (
    "capture is shorter than the required duration",
    "has too few detected timing markers",
    "marker onset exceeded the threshold",
    "post-start silence gap exceeded the threshold",
    "marker displacement exceeded the threshold",
    "marker sequence indicates replayed or out-of-order audio",
    "simultaneous markers indicate overlapping audio",
)


def failure_sides(failures: list[str]) -> tuple[list[str], list[str], list[str]]:
    leader: list[str] = []
    follower: list[str] = []
    pair: list[str] = []
    for failure in failures:
        if failure.startswith("reference "):
            follower.append(failure.removeprefix("reference "))
        elif failure in STREAM_FAILURE_SUFFIXES:
            leader.append(failure)
        else:
            pair.append(failure)
    return leader, follower, pair


def classify(report: dict, leader_feed: dict | None = None,
             follower_feed: dict | None = None) -> dict:
    failures = [str(value) for value in report.get("failures", [])]
    leader, follower, pair = failure_sides(failures)
    leader_set = set(leader)
    follower_set = set(follower)

    if not failures:
        classification = "pass"
        explanation = "Both clients passed the strict timing policy on one recorder clock."
    elif "inter-client skew exceeded the threshold" in pair:
        classification = "inter-client-divergence"
        explanation = (
            "The clients diverged within one four-channel recording, so independent recorder "
            "clock recovery cannot explain the failure."
        )
    elif leader_set and follower_set and leader_set == follower_set:
        classification = "shared-upstream-or-graph-distortion"
        explanation = (
            "Both client pairs contain the same strict timing failure on one recorder clock; "
            "the remaining boundary is the shared source/feed or shared audio graph."
        )
    elif bool(leader_set) != bool(follower_set):
        classification = "single-client-render-path"
        explanation = (
            "Only one client pair failed while both were recorded together, localizing the "
            "defect after the shared source boundary."
        )
    else:
        classification = "indeterminate"
        explanation = (
            "The shared-clock evidence removes independent recorder drift but does not isolate "
            "this combination of failures further."
        )

    leader_feed_failures = [] if leader_feed is None else [
        str(value) for value in leader_feed.get("failures", [])
    ]
    follower_feed_failures = [] if follower_feed is None else [
        str(value) for value in follower_feed.get("failures", [])
    ]
    if failures and leader_feed is not None and follower_feed is not None:
        if leader_feed_failures or follower_feed_failures:
            if bool(leader_feed_failures) != bool(follower_feed_failures):
                classification = "single-client-pcm-feed"
                explanation = (
                    "A one-sided defect is already present in the PCM supplied to the audio "
                    "backend; the shared recorder graph is downstream of the failure."
                )
            else:
                classification = "shared-pcm-feed-or-source"
                explanation = (
                    "Both pre-backend PCM feeds fail, localizing the defect before the shared "
                    "recorder graph."
                )
        elif leader_set or follower_set:
            if bool(leader_set) != bool(follower_set):
                classification = "single-client-audio-backend"
                explanation = (
                    "Both pre-backend PCM feeds pass but one rendered pair fails on the shared "
                    "clock, localizing the defect to that client's backend/render route."
                )
            else:
                classification = "shared-audio-graph-after-feed"
                explanation = (
                    "Both pre-backend PCM feeds pass while both rendered pairs fail, localizing "
                    "the defect after the client feed boundary in the shared audio graph."
                )

    return {
        "classification": classification,
        "explanation": explanation,
        "independent_recorder_ambiguity_removed": True,
        "leader_failures": leader,
        "follower_failures": follower,
        "pair_failures": pair,
        "leader_feed_failures": leader_feed_failures,
        "follower_feed_failures": follower_feed_failures,
        "pre_backend_pcm_available": leader_feed is not None and follower_feed is not None,
        "inter_client_skew_ms": report.get("inter_client_skew_ms"),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    parser.add_argument("--leader-feed-report", type=Path)
    parser.add_argument("--follower-feed-report", type=Path)
    args = parser.parse_args()
    report = json.loads(args.report.read_text("utf-8"))
    if bool(args.leader_feed_report) != bool(args.follower_feed_report):
        parser.error("both feed reports are required together")
    leader_feed = (json.loads(args.leader_feed_report.read_text("utf-8"))
                   if args.leader_feed_report else None)
    follower_feed = (json.loads(args.follower_feed_report.read_text("utf-8"))
                     if args.follower_feed_report else None)
    print(json.dumps(classify(report, leader_feed, follower_feed),
                     indent=2, sort_keys=True))


if __name__ == "__main__":
    main()

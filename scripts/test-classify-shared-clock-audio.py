#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("classify-shared-clock-audio.py")
SPEC = importlib.util.spec_from_file_location("classify_shared_clock_audio", SCRIPT)
assert SPEC and SPEC.loader
classifier = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = classifier
SPEC.loader.exec_module(classifier)


class SharedClockClassificationTests(unittest.TestCase):
    def report(self, *failures: str) -> dict:
        return {"failures": list(failures), "inter_client_skew_ms": 0}

    def test_pass(self) -> None:
        result = classifier.classify(self.report())
        self.assertEqual(result["classification"], "pass")
        self.assertTrue(result["independent_recorder_ambiguity_removed"])

    def test_one_sided_failure_is_client_render_path(self) -> None:
        result = classifier.classify(self.report(
            "marker displacement exceeded the threshold"
        ))
        self.assertEqual(result["classification"], "single-client-render-path")

    def test_matching_failures_are_shared(self) -> None:
        result = classifier.classify(self.report(
            "marker displacement exceeded the threshold",
            "reference marker displacement exceeded the threshold",
        ))
        self.assertEqual(
            result["classification"], "shared-upstream-or-graph-distortion"
        )

    def test_inter_client_skew_has_priority(self) -> None:
        result = classifier.classify(self.report(
            "marker displacement exceeded the threshold",
            "inter-client skew exceeded the threshold",
        ))
        self.assertEqual(result["classification"], "inter-client-divergence")

    def test_clean_feeds_localize_one_sided_render_failure_to_backend(self) -> None:
        result = classifier.classify(
            self.report("marker displacement exceeded the threshold"),
            self.report(), self.report(),
        )
        self.assertEqual(result["classification"], "single-client-audio-backend")

    def test_one_sided_feed_failure_precedes_the_backend(self) -> None:
        result = classifier.classify(
            self.report("marker displacement exceeded the threshold"),
            self.report("marker displacement exceeded the threshold"), self.report(),
        )
        self.assertEqual(result["classification"], "single-client-pcm-feed")


if __name__ == "__main__":
    unittest.main()

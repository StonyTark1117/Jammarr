#!/usr/bin/env python3
"""Regression tests for arbitrary live-audio pair alignment."""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from argparse import Namespace
from pathlib import Path

import numpy as np


SCRIPT = Path(__file__).with_name("analyze-live-audio.py")
SPEC = importlib.util.spec_from_file_location("analyze_live_audio", SCRIPT)
assert SPEC and SPEC.loader
analyzer = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = analyzer
SPEC.loader.exec_module(analyzer)


class LiveAudioAnalyzerTests(unittest.TestCase):
    RATE = 8000

    def material(self, seconds: int = 12) -> np.ndarray:
        rng = np.random.default_rng(1710)
        value = rng.normal(0.0, 1.0, self.RATE * seconds)
        kernel = np.hanning(41)
        return np.convolve(value, kernel / kernel.sum(), mode="same") * 8000.0

    def args(self) -> Namespace:
        return Namespace(
            minimum_duration_ms=10000,
            maximum_skew_ms=250,
            minimum_correlation=0.85,
            minimum_block_correlation=0.90,
            maximum_bad_run_ms=300,
            maximum_silence_ms=1000,
        )

    def test_shifted_identical_program_passes(self) -> None:
        left = self.material()
        right = np.concatenate((np.zeros(self.RATE // 10), left))
        report = analyzer.analyze_pair(left, right, self.RATE, 250, 100)
        self.assertEqual(analyzer.validate("pair", report, self.args()), [])
        self.assertAlmostEqual(abs(report["lag_ms"]), 100.0, delta=2.0)

    def test_lag_is_refined_beyond_the_decimation_grid(self) -> None:
        rate = 44100
        shift = 706
        rng = np.random.default_rng(1710)
        left = rng.normal(0.0, 1.0, rate * 2)
        right = np.concatenate((np.zeros(shift), left))
        lag = analyzer.best_lag(left, right, rate, rate // 4000)
        self.assertEqual(lag, -shift)
        left_common, right_common = analyzer.aligned(left, right, lag)
        self.assertGreater(analyzer.normalized_correlation(left_common, right_common), 0.999999)

    def test_joint_subaudible_recorder_prefix_is_not_divergence(self) -> None:
        material = self.material()
        prefix = self.RATE // 2
        left = np.concatenate((np.full(prefix, 0.1), material))
        right = np.concatenate((np.zeros(prefix), material))
        report = analyzer.analyze_pair(left, right, self.RATE, 250, 100)
        self.assertEqual(analyzer.validate("pair", report, self.args()), [])
        self.assertEqual(report["leading_joint_inactive_ms"], 500)

    def test_one_sided_audible_prefix_remains_divergent(self) -> None:
        material = self.material()
        prefix = self.RATE // 2
        left = np.concatenate((material[:prefix], material))
        right = np.concatenate((np.zeros(prefix), material))
        report = analyzer.analyze_pair(left, right, self.RATE, 250, 100)
        failures = analyzer.validate("pair", report, self.args())
        self.assertEqual(report["leading_joint_inactive_ms"], 0)
        self.assertTrue(any("divergent" in failure for failure in failures), failures)

    def test_small_same_content_startup_lag_adjustment_is_classified(self) -> None:
        material = self.material()
        settling = self.RATE * 2
        adjustment = self.RATE // 100
        right = np.concatenate(
            (
                np.zeros(adjustment),
                material[: settling - adjustment],
                material[settling:],
            )
        )
        report = analyzer.analyze_pair(material, right, self.RATE, 250, 100)
        self.assertEqual(analyzer.validate("pair", report, self.args()), [])
        self.assertEqual(report["raw_bad_block_count"], 20)
        self.assertEqual(report["bad_block_count"], 0)
        self.assertEqual(report["startup_settling_ms"], 2000)
        self.assertAlmostEqual(abs(report["startup_lag_adjustment_ms"]), 10.0, delta=1.0)
        self.assertGreater(report["startup_correlation"], 0.99)

    def test_large_startup_lag_adjustment_remains_divergent(self) -> None:
        material = self.material()
        settling = self.RATE * 2
        adjustment = self.RATE // 10
        right = np.concatenate(
            (
                np.zeros(adjustment),
                material[: settling - adjustment],
                material[settling:],
            )
        )
        report = analyzer.analyze_pair(material, right, self.RATE, 250, 100)
        failures = analyzer.validate("pair", report, self.args())
        self.assertTrue(any("divergent" in failure for failure in failures), failures)
        self.assertEqual(report["startup_settling_ms"], 0)

    def test_interior_small_lag_jump_remains_divergent(self) -> None:
        material = self.material()
        start = self.RATE * 4
        end = self.RATE * 6
        adjustment = self.RATE // 100
        right = np.concatenate(
            (
                material[:start],
                material[start - adjustment : end - adjustment],
                material[end:],
            )
        )
        report = analyzer.analyze_pair(material, right, self.RATE, 250, 100)
        failures = analyzer.validate("pair", report, self.args())
        self.assertTrue(any("divergent" in failure for failure in failures), failures)
        self.assertEqual(report["startup_settling_ms"], 0)

    def test_channel_timing_compares_authoritative_program_origin(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            left = Path(temporary) / "left.log"
            right = Path(temporary) / "right.log"
            left.write_text(
                "JAMMARR_AUDIO_TIMING stage=channel_started "
                "monotonicNanos=322989366837210 positionMs=140\n"
            )
            right.write_text(
                "JAMMARR_AUDIO_TIMING stage=channel_started "
                "monotonicNanos=322989366751399 positionMs=141\n"
            )
            report = analyzer.analyze_channel_timing(left, right)
        self.assertIsNotNone(report)
        self.assertAlmostEqual(report["start_delta_ms"], 0.086, delta=0.001)
        self.assertEqual(report["position_delta_ms"], -1)
        self.assertAlmostEqual(report["program_alignment_delta_ms"], 1.086, delta=0.001)

    def test_channel_timing_requires_complete_marker(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            left = Path(temporary) / "left.log"
            right = Path(temporary) / "right.log"
            left.write_text("no timing marker\n")
            right.write_text(
                "JAMMARR_AUDIO_TIMING stage=channel_started "
                "monotonicNanos=1 positionMs=0\n"
            )
            self.assertIsNone(analyzer.analyze_channel_timing(left, right))

    def test_skipped_region_fails(self) -> None:
        left = self.material()
        start = self.RATE * 5
        right = np.concatenate((left[:start], left[start + self.RATE // 2 :], np.zeros(self.RATE // 2)))
        report = analyzer.analyze_pair(left, right, self.RATE, 250, 100)
        failures = analyzer.validate("pair", report, self.args())
        self.assertTrue(any("divergent" in failure for failure in failures), failures)

    def test_reordered_region_fails(self) -> None:
        left = self.material()
        block = self.RATE
        right = left.copy()
        right[4 * block : 5 * block], right[5 * block : 6 * block] = (
            left[5 * block : 6 * block],
            left[4 * block : 5 * block],
        )
        report = analyzer.analyze_pair(left, right, self.RATE, 250, 100)
        failures = analyzer.validate("pair", report, self.args())
        self.assertTrue(any("divergent" in failure for failure in failures), failures)


if __name__ == "__main__":
    unittest.main()

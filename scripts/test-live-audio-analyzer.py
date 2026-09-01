#!/usr/bin/env python3
"""Regression tests for arbitrary live-audio pair alignment."""

from __future__ import annotations

import importlib.util
import sys
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

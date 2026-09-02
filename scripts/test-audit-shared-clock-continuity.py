#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
from pathlib import Path
import json
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("audit-shared-clock-continuity.py")
SPEC = importlib.util.spec_from_file_location("audit_shared_clock_continuity", SCRIPT)
assert SPEC and SPEC.loader
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


def fixture(slot_count: int = 40) -> list[int]:
    labels: list[int] = []
    for slot in range(slot_count):
        marker = AUDIT.timing.marker_type(slot)
        labels.extend([marker] * 18)
        labels.extend([-1] * 7)
    return labels


class SharedClockContinuityTest(unittest.TestCase):
    def test_capture_cycles_preserves_dotted_prefix(self) -> None:
        prefix = "1.20.1-fabric.mixed-client-churn"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            base = root / f"{prefix}-00001"
            Path(f"{base}.classification.json").write_text(
                json.dumps({"classification": "pass"}), encoding="utf-8"
            )
            Path(f"{base}.leader.s16le").touch()
            Path(f"{base}.follower.s16le").touch()
            cycles = AUDIT.capture_cycles(root, prefix)
        self.assertEqual(cycles, [(1, Path(f"{base}.leader.s16le"), Path(f"{base}.follower.s16le"))])

    def test_identity_envelope_finds_a_delayed_stream(self) -> None:
        reference = fixture()
        delayed = [-1] * 10 + reference[:-10]
        report = AUDIT.best_marker_lag(delayed, reference, 50)
        self.assertEqual(abs(int(report["lagBins"])), 10)
        self.assertGreater(float(report["score"]), 0.99)

    def test_binary_marker_type_cannot_hide_a_different_slot(self) -> None:
        reference = fixture()
        shifted = reference[25:] + reference[:25]
        report = AUDIT.best_marker_lag(shifted, reference, 50)
        self.assertEqual(abs(int(report["lagBins"])), 25)
        self.assertGreater(float(report["score"]), 0.99)

    def test_aligned_streams_prefer_zero_lag(self) -> None:
        reference = fixture()
        report = AUDIT.best_marker_lag(reference, reference, 50)
        self.assertEqual(report["lagBins"], 0)
        self.assertEqual(report["score"], 1.0)


if __name__ == "__main__":
    unittest.main()

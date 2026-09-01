#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("extract-pcm-trace-tail.py")
SPEC = importlib.util.spec_from_file_location("extract_pcm_trace_tail", SCRIPT)
assert SPEC and SPEC.loader
EXTRACTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EXTRACTOR)


class ExtractPcmTraceTailTest(unittest.TestCase):
    def test_reconstructs_tail_across_rotation_boundary(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = root / "pcm-feed-42-00003.s16le"
            second = root / "pcm-feed-42-00004.s16le"
            first.write_bytes(b"abcdefgh")
            second.write_bytes(b"ijkl")
            output = root / "tail.raw"
            paths = EXTRACTOR.newest_session(root)
            self.assertEqual(paths, [first, second])
            self.assertEqual(EXTRACTOR.extract(paths, output, 7), 7)
            self.assertEqual(output.read_bytes(), b"fghijkl")

    def test_selects_newest_session_and_supports_legacy_single_file(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            legacy = root / "legacy.s16le"
            legacy.write_bytes(b"old")
            current = root / "pcm-feed-99-00000.s16le"
            current.write_bytes(b"new")
            legacy.touch()
            current.touch()
            current_ns = legacy.stat().st_mtime_ns + 1_000_000
            import os
            os.utime(current, ns=(current_ns, current_ns))
            self.assertEqual(EXTRACTOR.newest_session(root), [current])


if __name__ == "__main__":
    unittest.main()

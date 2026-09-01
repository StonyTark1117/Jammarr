#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
from pathlib import Path
import random
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("tcp-impairment-proxy.py")
SPEC = importlib.util.spec_from_file_location("tcp_impairment_proxy", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
Profile = MODULE.Profile


class TcpImpairmentProfileTest(unittest.TestCase):
    def profile(self, root: Path, name: str) -> Profile:
        return Profile(name, root / "events.jsonl")

    def test_fixed_latency_delays_each_arrival_without_serializing_reads(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            profile = self.profile(Path(temporary), "latency-150ms")
            state: dict = {}
            rng = random.Random(1)
            first = profile.delivery_time("server-to-client", 100, 10.0, 10.000, 10.0, state, rng)
            second = profile.delivery_time("server-to-client", 100, 10.0, 10.001, first, state, rng)
            self.assertAlmostEqual(first, 10.075)
            self.assertAlmostEqual(second, 10.076)

    def test_jitter_preserves_tcp_order_when_later_delay_is_shorter(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            profile = self.profile(Path(temporary), "jitter-20-250ms")
            state: dict = {}
            rng = random.Random(3)
            previous = 20.0
            deliveries = []
            for offset in (0.000, 0.001, 0.002, 0.003):
                previous = profile.delivery_time(
                    "server-to-client", 100, 20.0, 20.0 + offset, previous, state, rng
                )
                deliveries.append(previous)
            self.assertEqual(deliveries, sorted(deliveries))

    def test_one_time_stall_holds_following_bytes_without_repeating_delay(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            profile = self.profile(Path(temporary), "stall-2s")
            state: dict = {}
            rng = random.Random(1)
            stalled = profile.delivery_time("server-to-client", 100, 30.0, 33.0, 30.0, state, rng)
            following = profile.delivery_time("server-to-client", 100, 30.0, 33.1, stalled, state, rng)
            self.assertEqual(stalled, 35.0)
            self.assertEqual(following, 35.0)
            self.assertTrue(state["stalled"])

    def test_overload_profile_serializes_bytes_at_twelve_kilobytes_per_second(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            profile = self.profile(Path(temporary), "overload-6s")
            state: dict = {}
            rng = random.Random(1)
            first = profile.delivery_time("server-to-client", 12_000, 40.0, 40.0, 40.0, state, rng)
            second = profile.delivery_time("server-to-client", 12_000, 40.0, 40.1, first, state, rng)
            self.assertEqual(first, 41.0)
            self.assertEqual(second, 42.0)

    def test_overload_profile_drains_backlog_at_six_second_recovery(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            profile = self.profile(Path(temporary), "overload-6s")
            state: dict = {}
            rng = random.Random(1)
            first = profile.delivery_time(
                "server-to-client", 120_000, 50.0, 50.0, 50.0, state, rng
            )
            queued = profile.delivery_time(
                "server-to-client", 12_000, 50.0, 50.1, first, state, rng
            )
            recovered = profile.delivery_time(
                "server-to-client", 12_000, 50.0, 56.1, queued, state, rng
            )
            self.assertEqual(first, 56.0)
            self.assertEqual(queued, 56.0)
            self.assertEqual(recovered, 56.1)


if __name__ == "__main__":
    unittest.main()

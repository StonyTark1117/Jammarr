#!/usr/bin/env python3
"""Generate deterministic stereo s16le audio for Jammarr timing acceptance."""

import argparse
import math
import struct
import sys


def marker_type(slot: int) -> int:
    """Stable non-periodic marker identity used to detect replayed/out-of-order PCM."""
    value = (slot + 0x9E3779B9) & 0xFFFFFFFF
    value ^= value >> 16
    value = (value * 0x7FEB352D) & 0xFFFFFFFF
    value ^= value >> 15
    value = (value * 0x846CA68B) & 0xFFFFFFFF
    value ^= value >> 16
    return value & 1


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--duration", type=float, required=True)
    parser.add_argument("--sample-rate", type=int, default=44100)
    args = parser.parse_args()
    if args.duration <= 0 or args.sample_rate < 8000:
        parser.error("duration and sample rate must be positive")

    stream = sys.stdout.buffer
    total = int(args.duration * args.sample_rate)
    block = bytearray()
    for index in range(total):
        time = index / args.sample_rate
        carrier = 0.22 * math.sin(2.0 * math.pi * 997.0 * time)
        marker_slot = int(time / 0.250)
        marker_frequency = 1477.0 if marker_type(marker_slot) == 0 else 1975.0
        # A 180 ms marker remains below the 250 ms cadence while giving
        # LWJGL 2 / Paulscode enough steady-state signal after MP3 decoding and
        # OpenAL resampling to classify both alternating frequencies reliably.
        marker = 0.42 * math.sin(2.0 * math.pi * marker_frequency * time) \
            if time % 0.250 < 0.180 else 0.0
        sample = max(-32768, min(32767, int((carrier + marker) * 32767.0)))
        block.extend(struct.pack("<hh", sample, sample))
        if len(block) >= 65536:
            stream.write(block)
            block.clear()
    if block:
        stream.write(block)


if __name__ == "__main__":
    main()

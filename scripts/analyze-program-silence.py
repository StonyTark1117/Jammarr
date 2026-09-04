#!/usr/bin/env python3
"""Detect the synthetic Plex program in a capture that may contain game audio."""

import argparse
import array
import json
import math
import sys
from pathlib import Path


def energy(samples: list[float], sample_rate: int, frequency: float) -> float:
    coefficient = 2 * math.cos(2 * math.pi * frequency / sample_rate)
    previous = previous_two = 0.0
    for sample in samples:
        value = sample + coefficient * previous - previous_two
        previous_two, previous = previous, value
    return max(0.0, previous * previous + previous_two * previous_two
               - coefficient * previous * previous_two)


def peak(samples: list[float], sample_rate: int, frequency: float) -> float:
    value = energy(samples, sample_rate, frequency)
    # A quarter-second window resolves peaks four hertz apart. Reject energy
    # leaking from a nearby musical note into the frequency being measured.
    if (value > energy(samples, sample_rate, frequency - 4)
            and value > energy(samples, sample_rate, frequency + 4)):
        return value
    return 0.0


def analyze(path: Path, sample_rate: int = 48_000) -> dict:
    raw = path.read_bytes()
    if sample_rate < 8_000 or len(raw) % 4 or len(raw) < sample_rate * 4 * 5 // 2:
        raise ValueError("Expected at least 2.5 seconds of complete stereo s16le frames")
    values = array.array("h")
    values.frombytes(raw)
    if sys.byteorder != "little":
        values.byteswap()
    samples = [float(value) for value in values[::2]]
    window, hop = sample_rate // 4, sample_rate // 8
    matching = longest = consecutive = total = 0
    for offset in range(sample_rate, len(samples) - window + 1, hop):
        frame = samples[offset:offset + window]
        found = False
        # Match both frequencies at the same rate, including the player's
        # bounded one-percent output-clock correction. Minecraft music can
        # contain a nearby carrier note or harmonic without this program pair.
        for adjustment in range(-10, 11):
            rate = 1 + adjustment / 1_000
            carrier = peak(frame, sample_rate, 997 * rate)
            carrier_rms = math.sqrt(2 * carrier) / window / 32_768
            if carrier_rms < 10 ** (-50 / 20):
                continue
            for marker_frequency in (1477, 1975):
                marker = peak(frame, sample_rate, marker_frequency * rate)
                if carrier * 0.3 < marker < carrier * 9:
                    found = True
                    break
            if found:
                break
        total += 1
        matching += int(found)
        consecutive = consecutive + 1 if found else 0
        longest = max(longest, consecutive)
    longest_ms = ((longest - 1) * hop + window) * 1_000 // sample_rate if longest else 0
    # Other game sounds may briefly mask one window of the program. Retain
    # identified program time across those windows instead of discarding it.
    detected_ms = ((matching - 1) * hop + window) * 1_000 // sample_rate if matching else 0
    return {
        "program_leak": detected_ms >= 1_500,
        "detected_program_ms": detected_ms,
        "longest_program_ms": longest_ms,
        "matching_windows": matching,
        "analyzed_windows": total,
        "ignored_startup_ms": 1_000,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    parser.add_argument("--sample-rate", type=int, default=48_000)
    args = parser.parse_args()
    try:
        result = analyze(args.capture, args.sample_rate)
    except (OSError, ValueError) as error:
        print(json.dumps({"error": str(error)}))
        return 2
    print(json.dumps(result, indent=2))
    return int(result["program_leak"])


if __name__ == "__main__":
    raise SystemExit(main())

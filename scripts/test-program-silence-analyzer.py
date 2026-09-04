#!/usr/bin/env python3
"""Check program detection against nearby music, quiet playback, and clock correction."""

import importlib.util
import math
import struct
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("program_silence", ROOT / "analyze-program-silence.py")
ORACLE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ORACLE)
SAMPLE_RATE = 8_000


def program(time: float, rate: float) -> float:
    position = time * rate
    marker = 1477 if int(position / 0.25) % 2 else 1975
    value = 0.22 * math.sin(2 * math.pi * 997 * position)
    if position % 0.25 < 0.18:
        value += 0.42 * math.sin(2 * math.pi * marker * position)
    return value


def music(time: float) -> float:
    return (0.07 * math.sin(2 * math.pi * 989 * time)
            + 0.04 * math.sin(2 * math.pi * 1975 * time)
            + 0.12 * math.sin(2 * math.pi * 389 * time))


def check(root: Path, name: str, signal, expected_leak: bool) -> None:
    path = root / (name + ".s16le")
    with path.open("wb") as output:
        for index in range(SAMPLE_RATE * 31 // 10):
            value = max(-32_768, min(32_767, round(signal(index / SAMPLE_RATE) * 32_767)))
            output.write(struct.pack("<hh", value, value))
    report = ORACLE.analyze(path, SAMPLE_RATE)
    if report["program_leak"] != expected_leak:
        raise AssertionError(f"{name}: expected leak={expected_leak}, got {report}")


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="jammarr-program-silence-") as directory:
        root = Path(directory)
        check(root, "silence", lambda t: 0, False)
        # These nearby musical notes reproduce the ambiguity in the
        # former 1000 Hz bandpass without using a copyrighted game recording.
        check(root, "nearby-music", music, False)
        check(root, "bounded-transition-tail", lambda t: program(t, 1) if t < 1.5 else 0, False)
        for rate in (0.99, 1.0, 1.01):
            for volume in (0.2, 1.0):
                check(root, f"program-{rate}-{volume}",
                      lambda t, r=rate, v=volume: program(t, r) * v, True)
            check(root, f"quiet-program-with-music-{rate}",
                  lambda t, r=rate: program(t, r) * 0.2 + music(t), True)
        incomplete = root / "incomplete.s16le"
        for raw in (b"", b"\0" * (SAMPLE_RATE * 4), b"\0" * (SAMPLE_RATE * 12 + 1)):
            incomplete.write_bytes(raw)
            try:
                ORACLE.analyze(incomplete, SAMPLE_RATE)
            except ValueError:
                pass
            else:
                raise AssertionError("Incomplete captures must fail rather than count as silence")
    print("Program silence analyzer distinguishes music and detects quiet, mixed, rate-corrected leaks")


if __name__ == "__main__":
    main()

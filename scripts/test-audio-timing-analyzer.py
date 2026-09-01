#!/usr/bin/env python3
"""Self-test the timing oracle against early, late, reordered, and overlapping PCM."""

import array
import json
import math
import struct
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent
SAMPLE_RATE = 44_100
FRAME_BYTES = 4


def byte_offset(milliseconds: int) -> int:
    return milliseconds * SAMPLE_RATE * FRAME_BYTES // 1_000


def marker_type(slot: int) -> int:
    value = (slot + 0x9E3779B9) & 0xFFFFFFFF
    value ^= value >> 16
    value = (value * 0x7FEB352D) & 0xFFFFFFFF
    value ^= value >> 15
    value = (value * 0x846CA68B) & 0xFFFFFFFF
    value ^= value >> 16
    return value & 1


def analyze(path: Path) -> tuple[int, dict]:
    result = subprocess.run([
        sys.executable, str(ROOT / "analyze-audio-timing.py"), str(path),
        "--sample-rate", str(SAMPLE_RATE), "--minimum-duration-ms", "10000",
    ], check=False, capture_output=True, text=True)
    return result.returncode, json.loads(result.stdout)


def write_offset_fixture(path: Path, start_slot: int, seconds: int = 12) -> None:
    block = bytearray()
    with path.open("wb") as output:
        for index in range(seconds * SAMPLE_RATE):
            time = index / SAMPLE_RATE
            carrier = 0.22 * math.sin(2.0 * math.pi * 997.0 * time)
            local_slot = int(time / 0.250)
            marker_frequency = 1477.0 if marker_type(start_slot + local_slot) == 0 else 1975.0
            marker = 0.42 * math.sin(2.0 * math.pi * marker_frequency * time) \
                if time % 0.250 < 0.180 else 0.0
            sample = max(-32768, min(32767, int((carrier + marker) * 32767.0)))
            block.extend(struct.pack("<hh", sample, sample))
            if len(block) >= 65536:
                output.write(block)
                block.clear()
        output.write(block)


def require_failure(path: Path, phrase: str) -> None:
    status, report = analyze(path)
    failures = report.get("failures", [])
    if status == 0 or not any(phrase in failure for failure in failures):
        raise AssertionError(f"{path.name} did not trigger {phrase!r}: {failures}")


def mix_opposite_marker(raw: bytes, target_slot: int) -> bytes:
    donor_slot = target_slot + 1
    while marker_type(donor_slot) == marker_type(target_slot):
        donor_slot += 1
    values = array.array("h")
    values.frombytes(raw)
    if sys.byteorder != "little":
        values.byteswap()
    target_frame = target_slot * SAMPLE_RATE // 4
    donor_frame = donor_slot * SAMPLE_RATE // 4
    frames = SAMPLE_RATE * 180 // 1_000
    for frame in range(frames):
        for channel in range(2):
            target = (target_frame + frame) * 2 + channel
            donor = (donor_frame + frame) * 2 + channel
            values[target] = max(-32_768, min(32_767, values[target] + values[donor]))
    if sys.byteorder != "little":
        values.byteswap()
    return values.tobytes()


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="jammarr-audio-timing-") as directory:
        root = Path(directory)
        clean = root / "clean.s16le"
        with clean.open("wb") as output:
            subprocess.run([
                sys.executable, str(ROOT / "generate-audio-fixture.py"),
                "--duration", "12", "--sample-rate", str(SAMPLE_RATE),
            ], check=True, stdout=output)
        raw = clean.read_bytes()

        status, report = analyze(clean)
        if status != 0 or report.get("failures"):
            raise AssertionError(f"clean timing fixture failed: {report.get('failures')}")

        boundary = root / "phase-4096.s16le"
        write_offset_fixture(boundary, 4096)
        status, report = analyze(boundary)
        if status != 0 or report.get("failures"):
            raise AssertionError(
                "valid marker identities after the old 1,024-second phase boundary failed: "
                f"{report.get('failures')}"
            )
        if report["capture"].get("marker_sequence_phase", 0) < 4096 \
                or report["capture"].get("marker_sequence_mismatches") != 0:
            raise AssertionError("analyzer did not recover a clean phase beyond 4095")

        start = byte_offset(4_000)
        displacement = byte_offset(120)
        early = root / "early.s16le"
        early.write_bytes(raw[:start] + raw[start + displacement:])
        require_failure(early, "marker displacement")

        late = root / "late.s16le"
        late.write_bytes(raw[:start] + raw[start:start + displacement] + raw[start:])
        require_failure(late, "marker displacement")

        # Replace the next three seconds with a replay of the preceding three. The
        # duration and 250 ms cadence stay intact, so only the absolute marker
        # identities can expose the stale/out-of-order PCM.
        replay_start = byte_offset(3_000)
        block = byte_offset(3_000)
        second = replay_start + block
        reordered = root / "reordered.s16le"
        reordered.write_bytes(raw[:second] + raw[replay_start:replay_start + block]
                              + raw[second + block:])
        require_failure(reordered, "replayed or out-of-order")

        overlap = root / "overlap.s16le"
        overlap.write_bytes(mix_opposite_marker(raw, 16))
        require_failure(overlap, "overlapping audio")

    print("Audio timing analyzer rejects early, late, reordered, and overlapping PCM")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "run-shared-clock-audio-qualification.sh"


class SharedClockAudioQualificationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = SCRIPT.read_text("utf-8")

    def test_escalates_only_after_bounded_stages(self) -> None:
        source = self.source
        self.assertIn("probe)", source)
        self.assertIn("canary)", source)
        self.assertIn("qualification)", source)
        self.assertIn("soak)", source)
        self.assertIn("churn_cycles=3", source)
        self.assertIn("churn_seconds=1800", source)
        self.assertIn("churn_seconds=7200", source)
        self.assertIn("fixture_seconds=9000", source)

    def test_uses_isolated_pipewire_and_unique_evidence_by_default(self) -> None:
        source = self.source
        self.assertIn('JAMMARR_OPENAL_DRIVER="${JAMMARR_OPENAL_DRIVER:-pipewire}"', source)
        self.assertIn("build/shared-clock-$stage-$run_id-$target", source)
        self.assertIn("JAMMARR_QUALIFICATION_OUTPUT_ROOT", source)
        self.assertIn("JAMMARR_AUDIO_CLIENT_GATE=true", source)


if __name__ == "__main__":
    unittest.main()

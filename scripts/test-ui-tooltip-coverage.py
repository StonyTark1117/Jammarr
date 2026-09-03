#!/usr/bin/env python3
"""Audit issue-#2 hover help across every distinct screen implementation."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class UiTooltipCoverageTest(unittest.TestCase):
    def test_every_legacy_main_screen_has_lookup_and_unit_coverage(self) -> None:
        screens = sorted(ROOT.glob("platforms/**/src/main/java/**/client/LegacyScreen.java"))
        self.assertEqual(len(screens), 11)
        for screen in screens:
            with self.subTest(screen=screen.relative_to(ROOT)):
                source = screen.read_text("utf-8")
                lookup = screen.with_name("LegacyUiTooltips.java")
                test = Path(
                    str(screen).replace("/src/main/java/", "/src/test/java/")
                ).with_name("LegacyScreenTest.java")
                self.assertTrue(lookup.is_file())
                self.assertTrue(test.is_file())
                self.assertIn("LegacyUiTooltips.tooltip(id)", source)
                self.assertIn("String tooltip = tooltip(button.id)", source)
                self.assertTrue(
                    "renderTooltip" in source
                    or "drawHoveringText" in source
                    or "drawTooltip(tooltip, mouseX, mouseY)" in source
                )
                lookup_source = lookup.read_text("utf-8")
                test_source = test.read_text("utf-8")
                self.assertIn('case 0: return "Show current shared playback and its source"', lookup_source)
                self.assertIn('case 56: return "Pause or resume shared playback for everyone"', lookup_source)
                self.assertIn("everyMainMenuControlHasHoverHelp", test_source)
                self.assertIn("for (int id = 50; id <= 63; id++)", test_source)

    def test_every_modern_main_screen_describes_tabs_actions_and_volume(self) -> None:
        screens = [ROOT / "src/main/java/stonytark/jammarr/client/JammarrScreen.java"]
        screens.extend(
            sorted(ROOT.glob("platforms/*/common/src/main/java/**/client/JammarrScreen.java"))
        )
        self.assertEqual(len(screens), 15)
        for screen in screens:
            with self.subTest(screen=screen.relative_to(ROOT)):
                source = screen.read_text("utf-8")
                self.assertIn("tabTooltip(View view)", source)
                self.assertIn("addAction(String label, String tooltip", source)
                self.assertIn("Set Jammarr volume on this client only", source)
                self.assertTrue(
                    "setTooltip(Tooltip.create" in source or "tooltips.put(" in source
                )
                if "platforms/mc26." in str(screen):
                    self.assertIn("private static int opaque(int rgb)", source)
                    self.assertNotRegex(source, r"centeredText\([^\n]*,\s*0x[0-9A-Fa-f]{6}\)")

        for version in ("26.1.2", "26.2"):
            config = ROOT / f"platforms/mc{version}/common/src/main/java/stonytark/jammarr/client/JammarrClientConfigScreen.java"
            config_source = config.read_text("utf-8")
            self.assertIn("private static int opaque(int rgb)", config_source)

    def test_runtime_gate_keeps_three_real_hover_rendering_canaries(self) -> None:
        gate = (ROOT / "scripts/run-dedicated-server-gate.sh").read_text("utf-8")
        self.assertIn(
            '$1 == "1.7.10-forge" || $1 == "1.18.2-fabric" || $1 == "1.20.1-fabric"',
            gate,
        )
        self.assertIn("Acceptance legacy hover help rendered on a real control", gate)
        self.assertIn("Acceptance hover help rendered on a real control", gate)

    def test_modern_blur_guard_covers_every_post_1216_screen_family(self) -> None:
        screens = [ROOT / "src/main/java/stonytark/jammarr/client/JammarrScreen.java"]
        screens.extend(
            ROOT / f"platforms/mc{version}/common/src/main/java/stonytark/jammarr/client/JammarrScreen.java"
            for version in ("1.21.9", "1.21.10", "1.21.11")
        )
        for screen in screens:
            with self.subTest(screen=screen.relative_to(ROOT)):
                source = screen.read_text("utf-8")
                self.assertIn("renderMenuBackground(graphics);", source)
                self.assertNotIn("renderBackground(graphics, mouseX, mouseY, partialTick)", source)


if __name__ == "__main__":
    unittest.main()

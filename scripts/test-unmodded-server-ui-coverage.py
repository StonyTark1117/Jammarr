#!/usr/bin/env python3

from __future__ import annotations

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parent.parent
NOTICE = "This server does not support Jammarr"
PROPERTY = "jammarr.acceptance.unmoddedServerProbe"
EVIDENCE = "Acceptance Jammarr unsupported-server screen remained open"


class UnmoddedServerUiCoverageTest(unittest.TestCase):
    def client_state_sources(self) -> list[Path]:
        candidates = [
            ROOT / "src/main/java/stonytark/jammarr/client/JammarrClientState.java",
            *ROOT.glob(
                "platforms/*/*/src/main/java/stonytark/jammarr/client/*ClientState.java"
            ),
        ]
        return sorted(
            path for path in candidates if path.is_file() and NOTICE in path.read_text("utf-8")
        )

    def test_every_unsupported_server_state_has_a_rendered_acceptance_probe(self) -> None:
        sources = self.client_state_sources()
        self.assertTrue(sources, "no client states expose the unsupported-server notice")
        for source in sources:
            with self.subTest(source=source.relative_to(ROOT)):
                text = source.read_text("utf-8")
                self.assertIn(f'Boolean.getBoolean("{PROPERTY}")', text)
                self.assertIn(f'"{NOTICE}".equals(notice)', text)
                self.assertIn(EVIDENCE, text)
                self.assertIn("instanceof ", text[text.index(PROPERTY) : text.index(EVIDENCE)])
                if source.name == "LegacyClientState.java":
                    self.assertRegex(
                        text,
                        re.compile(
                            r"if \(!helloSent\) \{\s*hello\(\);\s*"
                            r"runAcceptanceScreenProbe\(\);\s*return;",
                            re.MULTILINE,
                        ),
                    )


if __name__ == "__main__":
    unittest.main()

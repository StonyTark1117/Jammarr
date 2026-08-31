#!/usr/bin/env python3
"""Tests for DiscPanel live Plex credential migration."""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("configure-discopanel-live-plex.py")
SPEC = importlib.util.spec_from_file_location("configure_discopanel_live_plex", SCRIPT)
assert SPEC and SPEC.loader
configuration = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = configuration
SPEC.loader.exec_module(configuration)


class DiscPanelLivePlexConfigurationTests(unittest.TestCase):
    def test_source_moves_token_to_environment_and_blanks_library(self) -> None:
        source = configuration.plex_source(
            b'plexUrl = "http://plex.invalid:32400"\n'
            b'plexToken = "private-token"\n'
            b'musicLibrary = "Music"\n'
        )
        self.assertEqual(source.token, "private-token")
        self.assertIn(b'plexToken = ""', source.config)
        self.assertIn(b'musicLibrary = ""', source.config)
        self.assertNotIn(b"private-token", source.config)

    def test_source_rejects_wrong_explicit_library(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "Music library"):
            configuration.plex_source(
                b'plexUrl = "http://plex.invalid:32400"\n'
                b'plexToken = "private-token"\n'
                b'musicLibrary = "ASMR"\n'
            )

    def test_replace_requires_exact_single_key(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "exactly once"):
            configuration.replace_quoted_value("plexToken = \"a\"\nplexToken = \"b\"", "plexToken", "")

    def test_missing_only_preserves_existing_different_config(self) -> None:
        self.assertEqual(
            configuration.target_action(
                b'existing = "private"\n',
                b'expected = "sanitized"\n',
                False,
                replace_existing=False,
                missing_only=True,
            ),
            "skip-existing",
        )

    def test_sanitized_config_with_missing_environment_token_is_configured(self) -> None:
        expected = b'plexToken = ""\n'
        self.assertEqual(
            configuration.target_action(
                expected,
                expected,
                False,
                replace_existing=False,
                missing_only=True,
            ),
            "configure",
        )


if __name__ == "__main__":
    unittest.main()

# Changelog

## 1.0.1 - 2026-08-27

- Fix Forge 1.7.10 clients crashing in LWJGL2/OpenAL after Forge starts overlapping sound-engine reloads, including the `nalGetSourcei` and `nalSourcef` native-link failures seen on Linux.
- Let the initial 1.7.10 sound loader settle during normal startup, recover safely from an unusable or reloaded sound engine, and restore vanilla music scheduling when Jammarr no longer owns playback.
- Replace fragile modern SoundManager/SoundEngine accessor Mixins with runtime type-based field lookup, fixing Forge and NeoForge audio startup across mapped and production runtimes.
- Retain protocol 5, the existing server configuration format, and the complete 16-artifact / 21-runtime compatibility matrix.

## 1.0.0 - 2026-08-25

- Initial Jammarr release.

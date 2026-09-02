# Jammarr 1.1.0 final user acceptance

The automated matrix already covers every declared runtime. Final user acceptance is a short subjective/interactive check of the frozen candidate, not another manual 99-profile matrix.

All profiles are intentionally stopped. Start only the profile being tested in DiscPanel and stop it when finished.

## Required checks

### 1. Forge 1.7.10 search field and live playback

- Profile: `Jammarr 1.7.10 Forge Test`
- Address: `192.168.1.73:25567`
- Open Jammarr and click the Search field.
- Type several characters, backspace, and type again without pressing Search after every key.
- Allow the Now Playing state to refresh while the field remains open.
- Confirm the text, focus, caret, and selection do not reset or disappear.
- Submit the search, start a result, and listen through at least one track transition or manual skip.
- Confirm there is no repeated, missing, late, or out-of-order audio segment.

Result: [ ] Pass  [ ] Fail

Notes:

### 2. Modern representative playback

- Profile: `Jammarr 1.20.1 Fabric Test`
- Address: `192.168.1.73:25568`
- Connect with the matching Jammarr 1.1.0 Fabric client.
- Play long enough to hear a natural transition or perform a manual skip.
- Confirm the transition is continuous and both UI state and audio identify the same track.
- Exercise pause/resume and local volume once.

Result: [ ] Pass  [ ] Fail

Notes:

### 3. Optional unmodded client

- Connect a matching Minecraft client without Jammarr to either running test server.
- Confirm it joins and remains connected normally.
- Confirm it has no Jammarr screen, controls, or audio functionality.
- Confirm a Jammarr-enabled player can continue listening while the unmodded player is connected.

Result: [ ] Pass  [ ] Fail

Notes:

### 4. Plex library isolation and fallback

- Leave the test profile's `musicLibrary` selection blank so the `Music` metadata fallback is exercised.
- Browse and search ordinary music successfully.
- Search for a known item that exists only in the separate ASMR library.
- Confirm the ASMR-only item does not appear and cannot enter the shared queue.

Result: [ ] Pass  [ ] Fail

Notes:

### 5. Newest-version smoke

- Profile: `Jammarr 26.2 NeoForge Test`
- Address: `192.168.1.73:25587`
- Connect with the matching Jammarr 1.1.0 client, open the player, and play or resume one item.
- Confirm the UI, queue state, and audible output operate normally.

Result: [ ] Pass  [ ] Fail

Notes:

## Acceptance decision

- [ ] All required checks passed.
- [ ] GitHub issue #5 may be closed as user-validated.
- [ ] The frozen candidate may proceed to publication preparation without another code rebuild.

Tester:

Date:

Any failed check should include the profile, client artifact filename, approximate time, visible symptom, and relevant client/server log. A confirmed product failure creates a new candidate; a passing result does not trigger additional exploratory qualification.

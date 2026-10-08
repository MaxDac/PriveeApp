---
name: store-screenshots
description: Regenerate the F-Droid/store screenshots of Privee for Android (com.privee.app) with Roborazzi, which renders the real Compose screens on the JVM (Robolectric) with fake state, so no emulator or server is needed. Covers why adb screencap cannot be used (FLAG_SECURE), the record command, where the fake data lives (StoreScreenshotsTest), file naming under fastlane/metadata/android/en-US/images/phoneScreenshots, checking the images, and adding or changing a screen. Use when asked to take, update or regenerate screenshots, store images or listing graphics.
---

# Store screenshots

F-Droid reads the listing from `fastlane/metadata/android/en-US/`. The phone screenshots are in `images/phoneScreenshots/` and are named `1.png` to `N.png`, in display order. The current set is:

| File | Screen |
|---|---|
| `1.png` | Server choice |
| `2.png` | Welcome |
| `3.png` | Home (session list) |
| `4.png` | Conversation with a few messages |
| `5.png` | Safety number |

This file exists twice and the two copies must stay byte-identical:
- `.claude/skills/store-screenshots/SKILL.md`;
- `.github/skills/store-screenshots/SKILL.md`.

`scripts/tests/test_skill_copies.py` enforces this.

## Why not an emulator

`MainActivity` sets `FLAG_SECURE` in every build, debug included, with no toggle (see "Other apps on the device" in [`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md)). `adb shell screencap`, the emulator's camera button and screen recorders all return black frames. Do not add a switch to turn it off.

Instead, `app/src/testDebug/kotlin/com/privee/app/StoreScreenshotsTest.kt` renders the real screen composables (`ServerContent`, `WelcomeScreen`, `HomeContent`, `ChatContent`, `SafetyNumberDialog`) under Robolectric with native graphics, and Roborazzi writes the PNGs. It runs on a Pixel 7 device profile, in English (`en-rUS`), in the light theme and in UTC, so the output does not depend on the machine.

## 1. Edit the fake data (optional)

All content comes from constants and fake state at the top of `StoreScreenshotsTest`: the session names, the server URL and instance name, the messages and their times, and the safety number. Rules:
- use generated-looking session names and `example.org` addresses only;
- no real names, servers or personal data;
- keep the conversation short and friendly so it fits on one screen.

If a screen needs state its `*Content` composable cannot take yet, hoist it into a parameter of that composable (the stateful wrapper keeps passing the real value). Do not add test-only code paths to the app.

## 2. Record

From PowerShell on Windows (set `ANDROID_HOME` if `local.properties` is missing), or from a shell on Linux:

```powershell
./gradlew :app:testDebugUnitTest --tests "com.privee.app.StoreScreenshotsTest" -PrecordStoreScreenshots
```

`-PrecordStoreScreenshots` turns on `roborazzi.test.record` and points the output at `fastlane/metadata/android/en-US/images/phoneScreenshots/`, overwriting `1.png` to `5.png`. Without the flag (as in CI) the test only renders the screens and writes nothing. That still catches crashes, but pixels are never compared, because font rendering differs between operating systems.

## 3. Check the images

Open each PNG and check that:
- it is a portrait phone image, 1078x2399 for the Pixel 7 profile, the same size as the rest of the set;
- the content is right, with no clipped text, and the dialog is visible in `5.png`;
- no keyboard, debug-only UI or personal data is visible.

The images have no status bar, because Robolectric renders only the app window. Dialogs fill the width a little more than on a real device. Both are expected.

## 4. Add or change a screen

1. Add a `@Test` to `StoreScreenshotsTest` that sets content with the stateless composable and calls `captureScreenRoboImage(File(dir, "N.png"))`.
2. Keep the numbering contiguous and update the table at the top of this skill (both copies).
3. Record, then check as above.

## 5. Commit

```powershell
git status          # only the PNGs (and StoreScreenshotsTest, if edited) should change
```

Screenshots usually ship in the next release-bump PR (`chore(release): X.Y.Z`; skill `fdroid-release`), because F-Droid picks up metadata from the release tag. They can also go in a separate `docs(store): refresh screenshots` PR.

For an end-to-end demo against a local server, follow the server's `local-dev-stack` skill (<https://github.com/MaxDac/Privee/tree/main/.claude/skills/local-dev-stack>), with the debug app on an emulator. Screen captures from that run will be black.

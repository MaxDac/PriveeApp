---
name: store-screenshots
description: Capture or refresh the F-Droid/store screenshots of Privee for Android (com.privee.app) using an Android emulator, a local Privee server and two app installs that chat with each other. Covers running the server from WSL, the debug build, a temporary second install, adb input and screencap on Windows, file naming under fastlane/metadata/android/en-US/images/phoneScreenshots, and cleanup. Use when asked to take, update or regenerate screenshots, store images or listing graphics, or to demo the app end to end on an emulator.
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

Background reading:
- [`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md), on how the app finds and checks a server;
- the server's `local-dev-stack` skill (<https://github.com/MaxDac/Privee/tree/main/.claude/skills/local-dev-stack>).

This file exists twice and the two copies must stay byte-identical:
- `.claude/skills/store-screenshots/SKILL.md`;
- `.github/skills/store-screenshots/SKILL.md`.

`scripts/tests/test_skill_copies.py` enforces this.

## 1. Start a current Privee server

The app needs `GET /api/app/info`, so use an up-to-date Privee `main`, not an old checkout.

```bash
# WSL, in a clone of https://github.com/MaxDac/Privee
export PATH=$HOME/.local/share/mise/shims:$PATH
mix setup                      # first time; needs Postgres (Windows service on localhost:5432 works)
PRIVEE_INSTANCE_NAME="Privee" PRIVEE_SOURCE_URL="https://github.com/MaxDac/Privee" mix phx.server
curl -s http://localhost:4000/api/app/info   # expect "service":"privee","api_version":1
```

The emulator reaches the host at `http://10.0.2.2:4000`. Debug builds accept `http://` and suggest that address.

## 2. Start the emulator and install the debug app

```powershell
emulator -list-avds
Start-Process emulator -ArgumentList '-avd','<avd>','-no-snapshot-save'
adb wait-for-device
./gradlew :app:installDebug          # com.privee.app.debug
```

Use a clean, recent phone image (Pixel, light theme, English). Set a tidy status bar with demo mode:

```bash
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
```

## 3. Add a second install to chat with

A conversation needs two sessions. Build a temporary second app id:

1. In `app/build.gradle.kts`, change the debug `applicationIdSuffix = ".debug"` to `".debug2"`.
2. Run `./gradlew :app:installDebug`.
3. **Revert the change immediately** (`git checkout -- app/build.gradle.kts`) and check that `git status` is clean.

Onboard both installs against `http://10.0.2.2:4000`, using "quick" sessions with generated names. Never use real names or a real server. Exchange a few friendly messages.

- Messages are relayed live, so open the chat on both installs while sending.
- Switch apps with `adb shell monkey -p com.privee.app.debug 1` (or `.debug2`).
- `adb shell input text` needs spaces written as `%s`: `adb shell input text "Hi%sthere"`.
- Find coordinates with `adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml`.

## 4. Capture

On Windows, never pipe `adb exec-out screencap` through a PowerShell redirect, because it corrupts the PNG. Capture on the device and pull the file instead:

```powershell
$dir = "fastlane/metadata/android/en-US/images/phoneScreenshots"
adb shell screencap -p /sdcard/shot.png; adb pull /sdcard/shot.png "$dir/1.png"
```

Then check each image:
- it is a valid PNG with portrait phone resolution;
- it shows no keyboard, unless the screen is about typing;
- no debug-only UI is visible;
- it contains no personal data.

## 5. Clean up and commit

```bash
adb uninstall com.privee.app.debug2
adb shell am broadcast -a com.android.systemui.demo -e command exit
git status          # only the PNGs should change; app/build.gradle.kts must be clean
```

Stop the emulator and the server.

Screenshots usually ship in the next release-bump PR (`chore(release): X.Y.Z`; skill `fdroid-release`), because F-Droid picks up metadata from the release tag. They can also go in a separate `docs(store): refresh screenshots` PR.

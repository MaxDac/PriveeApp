# Privee for Android

Native Android client for [Privee](https://github.com/MaxDac/Privee), an anonymous, end-to-end encrypted chat.

The app uses Kotlin and Jetpack Compose. It talks to the same Phoenix server as the website: a REST API under `/api/app` and Phoenix channels over WebSocket. It encrypts with the Signal protocol (PQXDH plus the Double Ratchet) via the official [libsignal](https://github.com/signalapp/libsignal) Android library, so it interoperates with the website, which uses the same library compiled to WebAssembly.

## Modules

| Module | Purpose |
| --- | --- |
| `app` | Compose UI, account storage, notifications, UnifiedPush |
| `core/net` | Privee REST API client and a minimal Phoenix channels client (OkHttp) |
| `core/signal` | Signal protocol wrapper: key bundles, session setup, encrypt/decrypt, safety numbers |

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the data flow and where to make changes, and [docs/TECHNOLOGIES.md](docs/TECHNOLOGIES.md) for the full technology stack, including how notifications work.

## Building

Requirements:
- JDK 17 or newer (the Android Studio JBR works).
- Android SDK platform `android-37.0` and build-tools `36.0.0`.

```sh
./gradlew test :app:lintDebug :app:assembleDebug
```

Debug builds use the application id `com.privee.app.debug`.

### Background listener device smoke test

The platform-only instrumentation runner uses a mock Phoenix server on the
device's loopback interface. It checks foreground/background and screen-off alerts, connection
status, reconnect/rejoin, the notification Stop action, foreground recovery,
and active sign-out without contacting a production server. No extra test
libraries or push providers are required.

Use a **fresh debug installation on a dedicated test device or emulator**.
The runner refuses to replace an existing signed-in account. On Android 13+,
grant notification permission before running:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell pm grant com.privee.app.debug android.permission.POST_NOTIFICATIONS
adb shell am instrument -w -r com.privee.app.debug.test/com.privee.app.push.BackgroundListenerInstrumentation
```

For earlier Android versions, omit the permission-grant command. Successful
execution reports `Passed:` and `INSTRUMENTATION_CODE: -1`. This mock-server
smoke test does not establish OEM-specific Doze behavior or guarantee delivery
after process termination; check those separately on representative devices.

For system restart checks, run the same runner with `-e prepareRestart true`.
It deliberately retains its local test account and enabled listener. Reopen
the app with `adb shell am start -W -n com.privee.app.debug/com.privee.app.MainActivity`
and wait for the foreground service before backgrounding it. Terminate only
that test app's exact process ID to check sticky recovery; **Force stop is a
different scenario** and must prevent automatic recovery. The mock server
closes when preparation finishes, so a recovered listener should show
reconnecting, not claim message delivery.

Afterwards, run with `-e cleanupRestart true` to sign out and disable the
fixture. Cleanup refuses to remove an account not created by this runner.

## Choosing a server

Privee has no default server: anyone can deploy their own (fork) of [Privee](https://github.com/MaxDac/Privee). On first launch the app asks for the address of a Privee server and checks it by calling `GET <server>/api/app/info`. It accepts the server only if the response has `"service": "privee"` and an `api_version` the app supports (currently `1`). No other screen is reachable until a server is selected. The server address can include a path (for example `https://example.org/privee`) when Privee runs under a sub-path.

- Release builds accept only `https://` servers.
- Debug builds also accept `http://`, and suggest `http://10.0.2.2:4000`, a local `mix phx.server` as seen from the Android emulator.

To switch servers, sign out and tap **Change server** on the welcome screen. The account and Signal state (keys, sessions and history) are stored per server, so one server never reuses another server's identity. Share links point to the selected server. The app link `privee://share/<session name>?server=<URL-encoded server>` opens a conversation directly only when its server is the selected one; otherwise (or for legacy links without `server`) the app names both servers and asks before opening. The welcome and home screens identify the server by its address; the `name` it reports about itself is only a secondary hint.

### Release signing

`./gradlew :app:assembleRelease` produces an unsigned APK unless signing is configured. To sign it, do one of the following:

- Create a `keystore.properties` file (gitignored) with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.
- Set the environment variables `PRIVEE_KEYSTORE_FILE`, `PRIVEE_KEYSTORE_PASSWORD`, `PRIVEE_KEY_ALIAS` and `PRIVEE_KEY_PASSWORD`.

## CI and releases

- **CI** (`.github/workflows/ci.yml`) runs on every push and pull request to `main`. It runs the tests, lint and the release-tooling tests, builds the debug APK, and checks that a declared release in `version.properties` has its changelog.
- **Release** (`.github/workflows/release.yml`) runs on demand. It builds a reproducible release APK, signs it with the release key and publishes a GitHub release with `release-manifest.json` and `SHA256SUMS`.
- **Reproducibility** (`.github/workflows/reproducibility.yml`) builds the APK the way F-Droid does, twice and independently, and requires identical results.

See [docs/RELEASING.md](docs/RELEASING.md) for setup and the release procedure.

## F-Droid

Privee is prepared for the official F-Droid repository; see [docs/FDROID.md](docs/FDROID.md).
- It has no Google Play Services dependencies. Background alerts can use a direct connection or [UnifiedPush](https://unifiedpush.org); see below.
- Release builds compile libsignal's native library from source at a pinned commit instead of using the prebuilt one from Maven ([libsignal/README.md](libsignal/README.md)). Release APKs are therefore ARM64 only.
- Builds are reproducible, so F-Droid publishes the same signed APK as the GitHub release, and you can switch between the two without reinstalling.
- Store metadata lives in `fastlane/metadata/android`; the F-Droid recipe is mirrored in `metadata/com.privee.app.yml`.

## Background message alerts

On the home screen, open **Background message alerts > Settings** and choose
**Enable listening**. Allow Android notification permission when prompted.
Privee keeps its existing connection to your selected server running in a
foreground service, even while the UI is closed. It uses no Firebase, Google
Play services, push gateway, or new backend integration.

An ongoing, quiet notification shows whether the listener is connected or
reconnecting. **Stop listening** disables the service without signing out;
normal foreground chat still works. Listening is off by default, its preference
survives ordinary process death, and signing out disables it. Android may restart
the service after ordinary termination, but restart and immediate delivery are
not guaranteed. There is no automatic start after reboot; reopen Privee.

Keeping a connection open costs battery. If alerts are delayed while the screen
is off, the settings guide links to Android battery optimization settings, where
you can optionally exempt Privee. Android's increased-battery-use warning describes
this trade-off; accepting it is optional, not an instruction to ignore security
warnings. Battery saver, Doze, manufacturer restrictions, disabled notification
channels, and network loss can still prevent delivery. Explicit **Force stop**
prevents alerts until you reopen the app.

The server announces live message arrivals; it does not replay missed notification
events on reconnect. Open a conversation to synchronize messages that arrived
while disconnected. Alerts never include message text.

Existing UnifiedPush support remains available with a distributor such as ntfy.
Unlike direct listening, it requires the server to send a wake-up signal through
the distributor's push server. While direct listening is enabled, distributor
alerts are suppressed to avoid duplicates; disable listening to use UnifiedPush
alerts instead. No distributor is needed for direct listening.

## Working on this repository (humans and AI agents)

- [CLAUDE.md](CLAUDE.md) (Claude Code) and [.github/copilot-instructions.md](.github/copilot-instructions.md) (GitHub Copilot) summarise the commands, conventions and guardrails.
- Step-by-step procedures are skills, kept as identical copies in `.claude/skills/` and `.github/skills/`:
  - `fdroid-release`
  - `libsignal-upgrade`
  - `dependency-upgrade`
  - `store-screenshots`
  - `cross-repo-change`
- Changes that also touch the server follow [Privee's cross-repo guide](https://github.com/MaxDac/Privee/blob/main/docs/cross-repo.md).

## License

Privee for Android is free software, licensed under the
[GNU Affero General Public License v3.0 only](LICENSE) (`AGPL-3.0-only`).
Every fork, modified version or derived work must stay under the same licence,
with its complete source code available to its users, including people who use a
modified version over a network. You cannot use this code in proprietary or
non-FOSS software. libsignal, which the app uses for encryption, is also
AGPL-3.0-only. See [NOTICE](NOTICE) for details and third-party components.

SPDX-License-Identifier: AGPL-3.0-only

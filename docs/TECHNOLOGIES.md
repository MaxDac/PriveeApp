# Technologies

This page lists everything Privee for Android is built with and why. The single
source of truth for library versions is
[`gradle/libs.versions.toml`](../gradle/libs.versions.toml); the versions below
are a snapshot, so check the catalogue before relying on one.

For the server (Elixir/Phoenix, the website and its WebAssembly libsignal) see
[Privee `docs/TECHNOLOGIES.md`](https://github.com/MaxDac/Privee/blob/main/docs/TECHNOLOGIES.md).
For how the pieces fit together see [ARCHITECTURE.md](ARCHITECTURE.md).

## Stack at a glance

| Area | Technology | Version | Notes |
|---|---|---|---|
| Language | Kotlin | 2.4.20 | JVM target 17, also used for the Compose compiler and serialization plugins |
| Build | Gradle (wrapper) | 9.6.1 | `gradle/wrapper/gradle-wrapper.properties` |
| Build | Android Gradle Plugin | 9.4.1 | `compileSdk` 37, `targetSdk` 36, `minSdk` 26 |
| Build | Core library desugaring | `desugar_jdk_libs` 2.1.5 | Required by libsignal-android on older API levels |
| UI | Jetpack Compose (BOM) | 2026.09.00 | Material 3; `material-icons-core` frozen at 1.7.8 |
| UI | Activity Compose | 1.11.0 | Single activity (`MainActivity`) |
| UI | Navigation Compose | 2.10.2 | Routes `welcome`, `register`, `login`, `home`, `chat/{name}` |
| UI | AndroidX Lifecycle | 2.9.3 | ViewModels, `ProcessLifecycleOwner` for foreground detection |
| Background | WorkManager | 2.12.0 | In the catalogue only; no module depends on it yet |
| Networking | OkHttp | 5.5.0 | REST and WebSocket; MockWebServer in tests |
| Networking | Phoenix channels client | in-house (`core/net/PhoenixSocket.kt`) | Serializer v2 over OkHttp WebSocket, heartbeats, reconnect, rejoin |
| Serialization | kotlinx.serialization JSON | 1.11.0 | |
| Concurrency | kotlinx.coroutines | 1.11.0 | `Flow`/`StateFlow` for UI state and channel events |
| Crypto | libsignal (`libsignal-client`, `libsignal-android`) | 0.86.5 | PQXDH + Double Ratchet; must match the website's libsignal WASM version |
| Local storage | Android Keystore + AES-GCM | platform | `EncryptedFileStorage`: one encrypted file per store, in `noBackupFilesDir` |
| Push | UnifiedPush connector | 3.3.5 | No Google Play Services / FCM |
| Tests | JUnit Jupiter (JUnit 6) | 6.1.3 | `./gradlew test`; `core/*` tests run on the plain JVM |
| Tests | Robolectric (JUnit 4 via the vintage engine) | 4.17 | `app` JVM tests that need Android: notifications, window protections, screenshots |
| Tests | Roborazzi | 1.76.0 | Renders the store screenshots from Compose on the JVM (`StoreScreenshotsTest`); records only with `-PrecordStoreScreenshots` |
| Release tooling | Python 3 + PyYAML 6.0.3 | | `scripts/*.py`, tested by `scripts/tests` |
| Release tooling | F-Droid buildserver image | `buildserver-trixie` (pinned digest) | `scripts/fdroid-rb-docker.sh`, the image fdroiddata CI uses |
| Native build | Rust nightly + Android NDK r28c | `nightly-2025-09-24`, NDK `28.2.13676358` | Only for release builds; see below |
| CI | GitHub Actions | | `ci.yml`, `reproducibility.yml`, `release.yml` |

## Notifications

Privee uses [UnifiedPush](https://unifiedpush.org), not Firebase Cloud
Messaging, so the app has no Google dependency and can ship on F-Droid.

- The user must install a UnifiedPush **distributor** (for example ntfy, or
  NextPush with Nextcloud). Without one the app works, but only notifies while
  it is open.
- `PushRegistration` registers with the distributor after sign-in.
  `PriveePushService.onNewEndpoint` sends the endpoint URL to the server
  (`PUT /api/app/push`); sign-out deletes it (`DELETE /api/app/push`).
  Every new sign-in sends the known endpoint again (`PushEndpointSync`),
  because the server drops it with the session, for example when it deletes
  an inactive session.
- The server's push is a **wake-up signal only**: it carries no message
  content and no sender. On a push, `PriveePushService.onMessage` shows a
  generic "new message" notification if the app is in the background. Message
  content is always fetched over the authenticated Phoenix socket and decrypted
  on the device.
- While the app is open, the `session` channel's `message_received` event
  drives notifications instead.
- Message notifications never name the sender: not in the title, the text, the
  lock-screen (public) version or the notification tag. Other apps with
  notification access can read all of those. All messages share one
  notification id. See
  [ARCHITECTURE.md#other-apps-on-the-device](ARCHITECTURE.md#other-apps-on-the-device).

## Encryption

- The Signal protocol via the official libsignal Java/Kotlin API, wrapped in
  `core/signal` (`SignalClient`, `PriveeProtocolStore`, `SignalState`).
- Keys, sessions and history live on the device only, encrypted at rest with a
  non-exportable Android Keystore AES-GCM key, and stored per server and per
  account. The server only sees public key material and ciphertext.
- Interoperability with the website depends on both using the same libsignal
  version. The protocol is documented in Privee's
  [`docs/e2e-encryption.md`](https://github.com/MaxDac/Privee/blob/main/docs/e2e-encryption.md).

## Native code: libsignal built from source

The Maven `libsignal-android` AAR ships a prebuilt `libsignal_jni.so`, which
F-Droid does not accept. With `-PlibsignalBuiltFromSource` (set by the F-Droid
recipe and the Release workflow), the build swaps in the `:libsignal:android`
module, built from source at a pinned commit by
`libsignal/scripts/build-libsignal.sh`.

- Pins live in [`libsignal/source.lock.json`](../libsignal/source.lock.json):
  tag and commit, rustup and its checksum, the Rust toolchain, the NDK
  revision, Cargo features, and the expected SHA-256 of the `.so`.
- Only `arm64-v8a` is built, so **release APKs are ARM64 only**. Debug builds
  and CI use the Maven artifacts.
- Details: [`libsignal/README.md`](../libsignal/README.md).

## Release and reproducibility

- Releases are manual (`release.yml`, `workflow_dispatch`) and version numbers
  come from `version.properties`.
- Builds are reproducible: the Reproducibility workflow builds each relevant
  PR twice (a replay in the buildserver image and a real `fdroid build`) and
  requires byte-identical APKs.
- F-Droid rebuilds each release, and since the result is identical it
  publishes **our** signed APK (`Binaries` + `AllowedAPKSigningKeys` in the
  recipe). Users can move between the GitHub and F-Droid APKs without
  reinstalling.
- `scripts/verify_release_apk.py` rejects an APK whose native libraries are not
  exactly `libsignal_jni.so` and `libandroidx.graphics.path.so`.

See [RELEASING.md](RELEASING.md), [FDROID.md](FDROID.md) and
[FDROID_VALIDATION.md](FDROID_VALIDATION.md).

## Deliberately not used

| Not used | Why |
|---|---|
| Google Play Services, Firebase, FCM | F-Droid inclusion and privacy; UnifiedPush instead |
| Analytics, crash reporting, ads | Privacy; F-Droid anti-features |
| Prebuilt native libraries | F-Droid policy; libsignal is built from source |
| Room / SQLite | State is small; Keystore-encrypted files are enough |
| Dependency injection frameworks | A hand-written `AppContainer` is enough |
| phoenix.js / a third-party Phoenix client | A small in-house client keeps `core/net` pure Kotlin and dependency-light |
| Play Integrity, root detection | Bypassable, and needs Google Play Services; see [ARCHITECTURE.md#other-apps-on-the-device](ARCHITECTURE.md#other-apps-on-the-device) |
| StrongBox, unlocked-device-required keys | Too slow for frequent Signal-state writes; would break background fetches while locked |
| Roborazzi Gradle plugin, screenshot verification in CI | The library alone is enough to record; pixels differ across operating systems |

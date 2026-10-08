# Architecture

How Privee for Android is put together, where to make common changes, and which
changes affect reproducible builds. For the stack and versions see
[TECHNOLOGIES.md](TECHNOLOGIES.md). For the server side and the shared
app-server contract see Privee's
[`docs/ARCHITECTURE.md`](https://github.com/MaxDac/Privee/blob/main/docs/ARCHITECTURE.md)
and [`docs/cross-repo.md`](https://github.com/MaxDac/Privee/blob/main/docs/cross-repo.md).

## Modules

```mermaid
flowchart LR
  app[":app<br/>Compose UI, storage,<br/>push, wiring"] --> net[":core:net<br/>REST + Phoenix channels"]
  app --> signal[":core:signal<br/>Signal protocol"]
  signal --> libsignal["libsignal-client (Maven)<br/>or :libsignal:android (source build)"]
  app --> libsignal
```

| Module | Plugin | Contents |
|---|---|---|
| `app` | Android application | `MainActivity` (navigation), `PriveeApplication`, `ui/` screens and ViewModels, `data/` (wiring, storage, sessions, conversations), `push/` (UnifiedPush, notifications) |
| `core/net` | Kotlin JVM | `PriveeApi` (REST client for `/api/app`), `PhoenixSocket` (channels client), `ServerInfo` (server check and URL normalisation), `Http` |
| `core/signal` | Kotlin JVM | `SignalClient` (key publication, sessions, encrypt/decrypt, outbox, history, safety numbers), `PriveeProtocolStore`, `SignalState` |
| `libsignal/android` | Android library | Only with `-PlibsignalBuiltFromSource`: libsignal's Java sources plus the source-built `libsignal_jni.so` |

**Rule:** `core/*` are plain Kotlin/JVM modules. They must not import
`android.*` or `androidx.*` (the PR template checks this). Anything that needs
Android goes in `app`, behind an interface such as `StateStorage`. This keeps
the protocol and network code testable with plain JUnit.

## Runtime wiring

`PriveeApplication` creates one `AppContainer`, a hand-written dependency
container:

- **`ServerStore`**: the selected server (`server.bin`).
- **`ActiveServer`**: the selected server's `PriveeApi`, its per-server directory
  `noBackupFilesDir/servers/<directoryName>/`, and its `AccountStore`.
- **`PriveeSession`**: exists while signed in. It owns:
  - the `PhoenixSocket`;
  - the `session` channel;
  - the `SignalClient`, whose state is stored in `signal-<sessionId>.bin` in the server directory.
- **`ChatConversation`**: one per open chat, using channel `chat:<peerName>`.

Everything stored is encrypted by `EncryptedFileStorage`, using AES-GCM under a non-exportable Android Keystore key. All of it lives under `noBackupFilesDir`, so it is never included in backups.

Conversation hints (a short private note on who a conversation is with) are
stored only in `PeerMeta.hint` inside the Signal state. They never go to the
server, push payloads or notifications. "Clear history" keeps them; signing
out and "Forget device" drop them. The editor (`HintDialog`) always advises
against writing the other person's name.

`PriveeApplication` also holds two device-local preferences, kept outside the
container and outside any account, so logging out or forgetting the device does
not reset them. They are never sent to a server, and `data_extraction_rules.xml`
keeps shared preferences out of backups and device transfers:

- **`LanguagePreferences`**: the app language.
- **`ThemePreferences`**: the theme's accent colour (`AppAccent`). Lilac is the default.

Both are changed from the Settings dialog (`ui/SettingsDialog.kt`).

## Data flow

```mermaid
sequenceDiagram
  participant U as User
  participant A as App
  participant S as Privee server
  participant D as UnifiedPush distributor
  U->>A: Enter server address
  A->>S: GET /api/app/info
  S-->>A: service "privee", api_version 1, name
  U->>A: Register / log in
  A->>S: POST /api/app/sessions (or sessions/log_in)
  S-->>A: token + session
  A->>S: WebSocket /app/socket/websocket (vsn 2.0.0, token in Sec-WebSocket-Protocol)
  A->>S: join "session": signal_status, publish_identity / add_prekeys / rotate_signed_prekey
  A->>D: UnifiedPush.register
  D-->>A: endpoint
  A->>S: PUT /api/app/push {endpoint}
  U->>A: Open chat with peer
  A->>S: join "chat:<peer>": request_peer_bundle, open_conversation, send_message
  S-->>D: wake-up (no content)
  D-->>A: onMessage → generic notification
```

1. **Choosing a server.** `ServerInfo.fetchServerInfo` calls
   `GET <server>/api/app/info`. It accepts the server only if `service` is
   `privee` and `api_version` is in `SUPPORTED_API_VERSIONS` (currently `{1}`).
   Release builds accept only `https://`.
2. **Authentication.** `PriveeApi` calls these endpoints, under `/api/app/`:

   | Method and path | Purpose |
   |---|---|
   | `POST sessions` | Register |
   | `POST sessions/log_in` | Log in |
   | `GET session` | Fetch the session |
   | `DELETE session` | Log out |
   | `PUT push` | Register the push endpoint |
   | `DELETE push` | Remove the push endpoint |

   Authenticated requests carry the bearer token.
3. **Socket.** `PhoenixSocket` connects to `<server>/app/socket/websocket?vsn=2.0.0`, sending the token as a `Sec-WebSocket-Protocol` entry, as phoenix.js does. It heartbeats, reconnects with backoff, and rejoins channels.
4. **Keys.** These run on the `session` channel. `SignalClient` reconciles keys with `signal_status` and publishes them through:
   - `publish_identity` and `reset_identity`;
   - `add_prekeys`;
   - `rotate_signed_prekey`.

   It reacts to the server events `replenish_prekeys`, `identity_superseded` and `message_received`.
5. **Messages.** These run on the `chat:<peer>` channel:
   - `request_peer_bundle` returns PQXDH bundles;
   - `open_conversation` returns the epoch;
   - `send_message` sends a message, with a client nonce and the epoch.

   The server event `new_message` or `peer_keys_ready` triggers `sync()`. Plaintext history stays on the device.
6. **Notifications:** see [TECHNOLOGIES.md#notifications](TECHNOLOGIES.md#notifications).

The authoritative description of these endpoints and events is the server's
[`docs/client-api.md`](https://github.com/MaxDac/Privee/blob/main/docs/client-api.md).

## Where to change what

| Change | Where | Also update |
|---|---|---|
| New screen or navigation route | `app/.../ui/`, `MainActivity` (`NavHost`) | Strings in `app/src/main/res/values/strings.xml` |
| New REST call | `core/net/PriveeApi.kt` + `PriveeApiTest` | Server first (see [cross-repo](https://github.com/MaxDac/Privee/blob/main/docs/cross-repo.md)) |
| New channel event or call | `PriveeSession` / `ChatConversation` (handlers), `SignalClient` (calls) | Server `client-api.md` |
| Server API version support | `ServerInfo.SUPPORTED_API_VERSIONS` + `ServerInfoTest` | README "Choosing a server" |
| Protocol or key handling | `core/signal` + `SignalClientTest` | Server `e2e-encryption.md` (both clients must agree) |
| Local storage format | `data/*Store.kt`, `SignalState` | Migrate old data (see `AppContainer.migrateLegacyData`) |
| Notifications | `push/Notifications.kt`, `push/PriveePushService.kt` | `MessageNotificationTest`; keep them generic (see [Other apps on the device](#other-apps-on-the-device)) |
| Window, overlay, accessibility or keyboard protections | `ui/DeviceProtection.kt`, `MainActivity` | `DeviceProtectionTest` |
| Theme colours or a new accent | `ui/Theme.kt` (`accentPalette`), `AppAccent` in `ThemePreferences.kt` | `ThemeTest` (contrast); colour names in every `strings.xml` |
| Device-local setting | `PriveeApplication`, `ui/SettingsDialog.kt` | Keep it out of `AppContainer` and server calls |
| Dependency versions | `gradle/libs.versions.toml` | Skill `dependency-upgrade` |
| libsignal version | `libs.versions.toml` + `libsignal/source.lock.json` | Skill `libsignal-upgrade`; server WASM version |
| Store listing | `fastlane/metadata/android/en-US/`; screenshots from `StoreScreenshotsTest` (Roborazzi) | Skill `store-screenshots` |
| Release | `version.properties` + `changelogs/<code>.txt` (release-bump PR only) | Skill `fdroid-release` |

## Changes that affect reproducible builds

The Reproducibility workflow runs (and its required checks do real work) when a
PR touches any of:

- `app/**`, `core/**`, `libsignal/**`, `gradle/**`;
- `*.gradle.kts`, `gradle.properties`, `version.properties`;
- `metadata/**`, `scripts/fdroid*`;
- `reproducibility.yml` or `release.yml`.

Pay special attention to:

- **Dependency or plugin upgrades.** They change `classes*.dex` and resources, and may add native libraries. `verify_release_apk.py` allows exactly two `.so` files.
- **Anything that embeds time, paths or randomness** in the APK, such as build timestamps, absolute paths or non-deterministic code generation.
- **The libsignal pins**, the NDK and the Rust toolchain. Changing any of them changes the `.so` hash, which must be re-pinned from a buildserver-image build.
- **The buildserver image digest** in `scripts/fdroid-rb-docker.sh`.

If the Reproducibility check fails, follow
[FDROID_VALIDATION.md](FDROID_VALIDATION.md) (diffoscope triage table).

## Other apps on the device

The threat model here is a malicious or over-privileged app on the same,
**non-rooted** phone: one that records the screen, draws overlays to trick
taps, reads the UI through an accessibility service, reads notifications
through a notification listener, or learns what is typed through the keyboard.
A rooted or compromised OS can read the app's memory, so it is out of scope.

| Threat | Defence | Where |
|---|---|---|
| Screenshots, screen recording, casting, the recents thumbnail | `FLAG_SECURE` on the window, in **every** build including debug, with no toggle. Compose dialogs and popups inherit it (`SecureFlagPolicy.Inherit`, the default; never override it). | `protectWindow` in `ui/DeviceProtection.kt`, called by `MainActivity.onCreate` before `setContent` |
| Overlays and tapjacking | API 31+: `Window.setHideOverlayWindows(true)` with the `HIDE_OVERLAY_WINDOWS` permission hides other apps' overlays while Privee is in front. API 26–30: `filterTouchesWhenObscured` on the decor view drops touches that pass through an overlay. | `protectWindow`, `protectViewTree`, `AndroidManifest.xml` |
| Accessibility-service scraping | API 34+: `setAccessibilityDataSensitive(YES)` on the decor view and on every dialog and popup window. Only services that declare `isAccessibilityTool` (TalkBack, Switch Access and similar) can read the UI. Compose views inherit the flag. | `protectViewTree`; `ProtectedWindow()` in `PriveeAlertDialog` and the dropdown menus |
| Keyboard learning | Every text field asks the keyboard not to learn from or remember input (`IME_FLAG_NO_PERSONALIZED_LEARNING`), through `InterceptPlatformTextInput` around the whole UI. The recovery phrase field is also `KeyboardType.Password`, so keyboards treat it as a password. | `NoPersonalizedLearning` in `ui/DeviceProtection.kt`, `AuthScreen` |
| Notification listeners and the lock screen | Message notifications always say just "New message", with no sender in the title, text, public (lock screen) version or tag. They use one fixed id, so the count of distinct senders is hidden too. Tapping opens the latest sender's chat through a `PendingIntent`, which other apps cannot read. | `push/Notifications.kt` |

Rules for new code:
- show dialogs with `PriveeAlertDialog` and call `ProtectedWindow()` inside any other `Dialog`, `Popup` or `DropdownMenu` content;
- never set `SecureFlagPolicy.SecureOff`;
- never put a sender, message text or a value derived from them in a notification;
- the app never writes to the clipboard; if that changes, mark the clip sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`).

Because of `FLAG_SECURE`, `adb screencap` returns black frames. Store
screenshots are rendered on the JVM by Roborazzi instead (skill `store-screenshots`).

Considered and rejected:

| Option | Why not |
|---|---|
| StrongBox for the storage key | Signal state is written often, and StrongBox is slow. The Keystore key is already non-exportable and hardware-backed where the device supports it. |
| `setUnlockedDeviceRequired` for the storage key | The key would be unusable while the phone is locked, which breaks background message fetches and notifications. |
| Root or emulator detection, Play Integrity | Easy to bypass on a rooted device, which is out of scope anyway. Play Integrity needs Google Play Services, which breaks F-Droid and de-Googled users. |
| Clipboard flags | The app never writes to the clipboard. |
| Biometric or PIN app lock | Defends against someone holding the unlocked phone, not against other apps. A possible separate feature. |

## Invariants

- Never change the release signing key or certificate. F-Droid publishes our APK only if it is signed with `AllowedAPKSigningKeys` (`ea586e3f…92f9`), and users cannot update across a key change.
- No Google Play Services, Firebase or other proprietary dependencies.
- No plaintext, keys or tokens in logs, notifications, push payloads or backups.
- `FLAG_SECURE` stays on in every build, and notifications never name the sender (see [Other apps on the device](#other-apps-on-the-device)).
- Per-server isolation: one server's identity, keys or history are never reused on another.

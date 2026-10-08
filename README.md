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
- It has no Google Play Services dependencies. Push notifications use [UnifiedPush](https://unifiedpush.org), so install a distributor such as ntfy to receive them while the app is closed.
- Release builds compile libsignal's native library from source at a pinned commit instead of using the prebuilt one from Maven ([libsignal/README.md](libsignal/README.md)). Release APKs are therefore ARM64 only.
- Builds are reproducible, so F-Droid publishes the same signed APK as the GitHub release, and you can switch between the two without reinstalling.
- Store metadata lives in `fastlane/metadata/android`; the F-Droid recipe is mirrored in `metadata/com.privee.app.yml`.

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

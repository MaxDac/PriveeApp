# Privee for Android

Native Android client for [Privee](https://github.com/MaxDac/Privee), an anonymous, end-to-end encrypted chat.

The app uses Kotlin and Jetpack Compose. It talks to the same Phoenix server as the website: a REST API under `/api/app` and Phoenix channels over WebSocket. It encrypts with the Signal protocol (PQXDH plus the Double Ratchet) via the official [libsignal](https://github.com/signalapp/libsignal) Android library, so it interoperates with the website, which uses the same library compiled to WebAssembly.

## Modules

| Module | Purpose |
| --- | --- |
| `app` | Compose UI, account storage, notifications, UnifiedPush |
| `core/net` | Privee REST API client and a minimal Phoenix channels client (OkHttp) |
| `core/signal` | Signal protocol wrapper: key bundles, session setup, encrypt/decrypt, safety numbers |

## Building

Requirements:
- JDK 17 or newer (the Android Studio JBR works).
- Android SDK platform `android-37.0` and build-tools `36.0.0`.

```sh
./gradlew test :app:lintDebug :app:assembleDebug
```

Debug builds use the application id `com.privee.app.debug` and connect to `http://10.0.2.2:4000`, which is a local `mix phx.server` as seen from the Android emulator. Release builds connect to `https://privee.fly.dev`.

### Release signing

`./gradlew :app:assembleRelease` produces an unsigned APK unless signing is configured. To sign it, do one of the following:

- Create a `keystore.properties` file (gitignored) with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.
- Set the environment variables `PRIVEE_KEYSTORE_FILE`, `PRIVEE_KEYSTORE_PASSWORD`, `PRIVEE_KEY_ALIAS` and `PRIVEE_KEY_PASSWORD`.

## CI and releases

- **CI** (`.github/workflows/ci.yml`) runs tests and lint and builds the debug APK on every push and pull request to `main`.
- **Release APK** (`.github/workflows/release.yml`) runs on demand from the Actions tab. It builds the release APK and uploads it as an artifact. If you give it a tag, it also publishes a GitHub release with the APK and its SHA-256.

The release workflow signs the APK when these repository secrets are set:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

## F-Droid

The app is designed to be F-Droid friendly:
- It has no Google Play Services dependencies. Push notifications use [UnifiedPush](https://unifiedpush.org), so install a distributor such as ntfy to receive them while the app is closed.
- The dependency-info block is disabled for reproducible builds.
- Store metadata lives in `fastlane/metadata/android`.

## License

[AGPL-3.0](LICENSE)

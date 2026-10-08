# CLAUDE.md: Privee for Android

Native Android client (`com.privee.app`, AGPL-3.0-only) for
[Privee](https://github.com/MaxDac/Privee), an anonymous end-to-end encrypted
chat. Kotlin + Jetpack Compose. The app talks to a Phoenix server (REST
`/api/app` plus channels) and encrypts with libsignal. It is published on
GitHub Releases and F-Droid, using reproducible builds signed by us.

## Read first

| Topic | Doc |
|---|---|
| Stack, versions, notifications, native code | [docs/TECHNOLOGIES.md](docs/TECHNOLOGIES.md) |
| Modules, data flow, where to change what, reproducibility-sensitive changes | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Releases and signing | [docs/RELEASING.md](docs/RELEASING.md) |
| F-Droid recipe, submission and MR loop | [docs/FDROID.md](docs/FDROID.md) |
| Local and buildserver builds, diffoscope | [docs/FDROID_VALIDATION.md](docs/FDROID_VALIDATION.md) |
| libsignal source build | [libsignal/README.md](libsignal/README.md) |
| App–server contract and change order | [Privee docs/cross-repo.md](https://github.com/MaxDac/Privee/blob/main/docs/cross-repo.md) |
| API, channels, protocol | [client-api.md](https://github.com/MaxDac/Privee/blob/main/docs/client-api.md), [e2e-encryption.md](https://github.com/MaxDac/Privee/blob/main/docs/e2e-encryption.md) |

## Skills (`.claude/skills/`)

| Skill | Use for |
|---|---|
| `fdroid-release` | Any release, the F-Droid submission or MR update, reviewer replies |
| `libsignal-upgrade` | Bumping libsignal or its NDK/Rust toolchain; `.so` hash pins |
| `dependency-upgrade` | Any other dependency, plugin, SDK, Gradle or Actions bump; Dependabot PRs |
| `store-screenshots` | Store screenshots; running the app end to end against a local server |
| `cross-repo-change` | Anything that also needs a server change |

Every skill also exists, byte-identical, in `.github/skills/` for Copilot. Edit
one copy, copy it over the other, and run the skill-copy test.

## Commands

```bash
./gradlew test :app:lintDebug :app:assembleDebug     # what CI runs (JDK 17+, SDK android-37.0, build-tools 36.0.0)
./gradlew :core:net:test :core:signal:test           # fast JVM-only tests
python3 -m unittest discover -s scripts/tests -v     # release tooling + skill-copy test
python3 scripts/release_version.py --check-version-properties
bash scripts/fdroid-rb-docker.sh out                 # F-Droid-identical release build (Docker, Linux/WSL)
```

On Windows:

- Gradle runs from PowerShell. Set `ANDROID_HOME` if `local.properties` is missing.
- Run the Python tooling and the bash scripts in WSL. Windows `python` may be the Microsoft Store stub.
- `gh` is installed on Windows only, so call it from PowerShell.
- WSL git cannot use a Windows worktree's `.git`, so run git from PowerShell.
- For screenshots, use `adb shell screencap` followed by `adb pull`, not a PowerShell redirect.

## Conventions

- Conventional Commits (`feat:`, `fix:`, `build(deps):`, `docs:`, `chore(release):`). Fill in `.github/pull_request_template.md`.
- PRs to `main` need four checks:
  - `Android (test, lint, assemble)`;
  - `Build (replay, release checks)`;
  - `Build (fdroid build)`;
  - `compare`.

  Squash-merge.
- `core/*` are plain Kotlin/JVM modules: no `android.*` or `androidx.*` imports.
- Add or update tests alongside code: JUnit 6, with MockWebServer for `core/net`.
- Comment only what needs explaining. Keep docs in sync when behaviour changes.

## Guardrails

- **Signing:** never change, regenerate or commit the release key. F-Droid only accepts certificate `ea586e3f…92f9`. Never print secrets.
- **Versions:** change `version.properties` and `fastlane/.../changelogs/<code>.txt` only in a release-bump PR (`chore(release): X.Y.Z`). Releases are manual, through `release.yml`.
- **Reproducibility:** no timestamps, absolute paths or non-determinism in the APK. Native libraries are limited to the two allowed by `scripts/verify_release_apk.py`. libsignal pins must stay consistent (`libs.versions.toml` and `libsignal/source.lock.json`).
- **F-Droid:** no Google Play Services, Firebase, analytics, trackers or prebuilt binaries. Never use `scanignore`.
- **Privacy:** no plaintext, keys or tokens in logs, notifications, push payloads or backups. State stays under `noBackupFilesDir`, encrypted.
- **Server compatibility:** respect `api_version` and the cross-repo change order (server first).
- **Upstream repos:** don't push to `fdroid/fdroiddata` directly. Use the fork `MaxDac/fdroiddata`, branch `com.privee.app`.

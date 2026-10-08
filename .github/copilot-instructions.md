# Copilot instructions: Privee for Android

This repository is the native Android client (`com.privee.app`,
AGPL-3.0-only) for [Privee](https://github.com/MaxDac/Privee), an anonymous
end-to-end encrypted chat. It is written in Kotlin with Jetpack Compose. It
talks to a Phoenix server (REST under `/api/app`, plus Phoenix channels) and
encrypts with libsignal. Releases go to GitHub and F-Droid as reproducible
builds signed by us.

## Project map

- `app/`: Compose UI (`ui/`), wiring and encrypted storage (`data/`), and UnifiedPush and notifications (`push/`).
- `core/net/`: the REST client (`PriveeApi`), the Phoenix channels client (`PhoenixSocket`) and the server check (`ServerInfo`). Plain Kotlin/JVM.
- `core/signal/`: the Signal protocol wrapper (`SignalClient`). Plain Kotlin/JVM.
- `libsignal/`: libsignal built from source for release and F-Droid builds (`source.lock.json` pins everything).
- `scripts/`: Python and bash release and F-Droid tooling, with tests in `scripts/tests/`.
- `metadata/com.privee.app.yml`: a mirror of the F-Droid recipe.
- `fastlane/metadata/android/`: the store listing.

Docs to read before non-trivial changes:
- [docs/TECHNOLOGIES.md](../docs/TECHNOLOGIES.md): stack and versions.
- [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md): data flow, where to change what, and which changes affect reproducibility.
- [docs/RELEASING.md](../docs/RELEASING.md), [docs/FDROID.md](../docs/FDROID.md) and [docs/FDROID_VALIDATION.md](../docs/FDROID_VALIDATION.md).
- Server and contract: [Privee docs/cross-repo.md](https://github.com/MaxDac/Privee/blob/main/docs/cross-repo.md) and [client-api.md](https://github.com/MaxDac/Privee/blob/main/docs/client-api.md).

## Skills

Procedures live in `.github/skills/<name>/SKILL.md`. Load the matching one before starting:

| Skill | When |
|---|---|
| `fdroid-release` | A release, the F-Droid submission or MR update, or an F-Droid reviewer reply |
| `libsignal-upgrade` | A libsignal, NDK or Rust toolchain bump, or a `.so` hash mismatch |
| `dependency-upgrade` | Any other dependency, plugin, SDK, Gradle or Actions bump, including Dependabot PRs |
| `store-screenshots` | Store screenshots, or an end-to-end run on an emulator against a local server |
| `cross-repo-change` | Changes that also need the server (MaxDac/Privee) |

`.claude/skills/` holds byte-identical copies for Claude Code. When you edit a
skill, update both copies; `scripts/tests/test_skill_copies.py` fails otherwise.

## Build and test

- CI runs `./gradlew test :app:lintDebug :app:assembleDebug`, with JDK 17+, SDK `android-37.0` and build-tools `36.0.0`.
- Release tooling and the skill-copy check: `python3 -m unittest discover -s scripts/tests -v`.
- An F-Droid-identical release build: `bash scripts/fdroid-rb-docker.sh out` (Docker, on Linux or WSL).
- On Windows:
  - run Gradle and `gh` from PowerShell;
  - run the Python and bash tooling in WSL;
  - run git from PowerShell, because WSL git cannot use a Windows worktree.

## Coding rules

- Modules under `core/` must not import `android.*` or `androidx.*`. Android-specific code goes in `app` behind an interface.
- Decode server JSON leniently (`ignoreUnknownKeys = true`) and ignore unknown channel events, so newer servers do not break older apps.
- Add tests with every change: JUnit 6, with MockWebServer for `core/net`.
- Put user-visible strings in `app/src/main/res/values/strings.xml`.
- Use Conventional Commits and fill in `.github/pull_request_template.md`.
- PRs need four checks:
  - `Android (test, lint, assemble)`;
  - `Build (replay, release checks)`;
  - `Build (fdroid build)`;
  - `compare`.

## Never

- Change, regenerate or commit the release signing key. F-Droid only accepts certificate `ea586e3f…92f9`.
- Edit `version.properties` or the changelogs outside a `chore(release): X.Y.Z` PR.
- Add Google Play Services, Firebase, analytics, trackers or prebuilt native libraries. Packaging a `.so` other than the two allowed by `scripts/verify_release_apk.py` counts too.
- Put message content, sender names or keys in logs, notifications, push payloads or backups.
- Introduce non-determinism into the APK: timestamps, absolute paths or random ordering.
- Change the app–server contract without following the server-first order in `cross-repo.md`.

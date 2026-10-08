---
name: dependency-upgrade
description: Upgrade Gradle, the Android Gradle Plugin, Kotlin, AndroidX/Compose, OkHttp, kotlinx, UnifiedPush or GitHub Actions in Privee for Android (com.privee.app) without breaking reproducible builds or F-Droid inclusion. Covers Dependabot PRs, the version catalogue, buildserver JDK/SDK/NDK constraints, the native-library allowlist, non-free dependency checks and the Reproducibility workflow. Use when asked to bump, update or upgrade a dependency, plugin, SDK level, Gradle wrapper or workflow action, or to review or merge a Dependabot PR. For libsignal use the libsignal-upgrade skill instead.
---

# Upgrade dependencies

Every published APK must be byte-identical when built by us and by F-Droid, and
must contain no proprietary code. Any dependency change can break either.

Background reading:
- [`docs/TECHNOLOGIES.md`](../../../docs/TECHNOLOGIES.md): the stack and why each part is there.
- [`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md): the section on changes that affect reproducible builds.
- [`docs/FDROID_VALIDATION.md`](../../../docs/FDROID_VALIDATION.md): local buildserver builds and diffoscope triage.

This file exists twice and the two copies must stay byte-identical:
- `.claude/skills/dependency-upgrade/SKILL.md`;
- `.github/skills/dependency-upgrade/SKILL.md`.

`scripts/tests/test_skill_copies.py` enforces this.

## Where versions live

| What | File |
|---|---|
| Libraries and plugins | `gradle/libs.versions.toml` (single source of truth) |
| Gradle | `gradle/wrapper/gradle-wrapper.properties` (use `./gradlew wrapper --gradle-version X`; this also updates the wrapper jar) |
| SDK levels, JVM target | `app/build.gradle.kts`, `core/*/build.gradle.kts` |
| CI SDK packages and JDK | `.github/workflows/ci.yml` (`platforms;android-NN`, `build-tools;X`, Java 17) |
| Actions | `.github/workflows/*.yml`. `reproducibility.yml` and `release.yml` pin actions by SHA, so keep doing that. |
| Buildserver image | `scripts/fdroid-rb-docker.sh` (digest) |
| Release tooling | `PyYAML==6.0.3` in `ci.yml` |

Dependabot (`.github/dependabot.yml`) opens weekly PRs for Gradle dependencies and Actions.

## Procedure

1. **One logical change per PR.** Change one library, or one coupled group (for example AGP plus Gradle, or Kotlin plus Compose compiler), in each PR. Title: `build(deps): bump <name> from A to B`.
2. **Check that the license is free.**
   - No Google Play Services, Firebase, GMS or proprietary SDKs.
   - Check that new transitive dependencies are not proprietary: `./gradlew :app:dependencies --configuration releaseRuntimeClasspath`.
3. **Check the buildserver can build it.**
   - F-Droid builds in `fdroidserver:buildserver-trixie` (Debian trixie, OpenJDK 21) with the SDK packages the recipe requests.
   - AGP or Gradle bumps that need a newer JDK, or a `compileSdk` not yet in the buildserver, will fail there even if CI passes.
   - If `compileSdk` or build-tools change, update `ci.yml` and the README requirements too.
4. **Build and test locally:**
   ```bash
   ./gradlew test :app:lintDebug :app:assembleDebug
   python3 -m unittest discover -s scripts/tests   # WSL on Windows
   ```
5. **Check the native libraries.**
   - `scripts/verify_release_apk.py` allows exactly two `.so` files: `lib/arm64-v8a/libsignal_jni.so` and `lib/arm64-v8a/libandroidx.graphics.path.so`.
   - If a bump adds a native library, either exclude it in `app/build.gradle.kts` (`packaging.jniLibs.excludes`) or stop and ask. A new prebuilt `.so` is usually not acceptable on F-Droid.
6. **Push the PR and wait for the four required checks:**
   - `Android (test, lint, assemble)`;
   - `Build (replay, release checks)`;
   - `Build (fdroid build)`;
   - `compare`.

   If `compare` fails, download the diffoscope artifact and use the triage table in FDROID_VALIDATION.md.
7. **Do not release in the same PR.** Version bumps go through the release-bump PR (skill `fdroid-release`).

## Special cases

| Upgrade | Watch out for |
|---|---|
| AGP | DSL changes in `app/build.gradle.kts`; `packaging` and `jniLibs.keepDebugSymbols` behaviour; dex differences between JDKs (the buildserver uses JDK 21) |
| Gradle wrapper | Commit `gradle-wrapper.jar` and the properties file together; `validateDistributionUrl` stays `true` |
| Kotlin | Compose compiler and serialization plugins share `kotlin` in the catalogue |
| Compose BOM | `material-icons-core` is pinned outside the BOM (1.7.8), so leave it there |
| UnifiedPush connector | Re-test push end to end with a distributor (ntfy); check `PriveePushService` API changes |
| OkHttp | `PhoenixSocket` WebSocket behaviour and `mockwebserver3` tests |
| desugar_jdk_libs | Required by libsignal-android; keep `isCoreLibraryDesugaringEnabled` |
| Buildserver image digest | Take it from fdroiddata's CI; rerun the Reproducibility workflow (see FDROID_VALIDATION.md) |
| libsignal | Use the `libsignal-upgrade` skill |

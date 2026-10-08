---
name: libsignal-upgrade
description: Upgrade libsignal in Privee for Android (com.privee.app) while keeping release builds reproducible and F-Droid-compatible. Covers the version catalogue, libsignal/source.lock.json (tag, commit, Rust toolchain, NDK, Cargo cfg, prune list), rebuilding libsignal_jni.so from source, re-pinning its SHA-256 from the F-Droid buildserver image, the Reproducibility workflow, and keeping the Privee website's libsignal WASM on the same version. Use when asked to bump, update or upgrade libsignal, change the NDK or Rust toolchain for libsignal, or when verifyLibsignal or the Reproducibility check fails on libsignal_jni.so.
---

# Upgrade libsignal

The app uses libsignal twice:
- in debug builds and CI, from Maven (`org.signal:libsignal-client` and `libsignal-android`);
- in release and F-Droid builds, built from source at a pinned commit (`-PlibsignalBuiltFromSource`).

Both must use the same version. That version must also match the libsignal WASM build used by the Privee website, because the two clients talk to each other.

Background reading:
- [`libsignal/README.md`](../../../libsignal/README.md): the source build and the bump checklist.
- [`docs/FDROID_VALIDATION.md`](../../../docs/FDROID_VALIDATION.md): buildserver builds and the diffoscope triage table.
- [`docs/TECHNOLOGIES.md`](../../../docs/TECHNOLOGIES.md): why libsignal is built from source.
- Server side: <https://github.com/MaxDac/Privee/blob/main/docs/cross-repo.md>, the libsignal alignment section.

This file exists twice and the two copies must stay byte-identical:
- `.claude/skills/libsignal-upgrade/SKILL.md`;
- `.github/skills/libsignal-upgrade/SKILL.md`.

`scripts/tests/test_skill_copies.py` enforces this.

## 0. Decide the target version

```bash
git ls-remote --tags https://github.com/signalapp/libsignal 'refs/tags/v*' | sort -t/ -k3 -V | tail -5
grep -n libsignal gradle/libs.versions.toml
```

Check which libsignal version the Privee website uses (see the cross-repo doc). Ship an upgrade together with the server, or after it. If the protocol changed between versions, read libsignal's release notes and Privee's `docs/e2e-encryption.md` before going ahead.

## 1. Bump the pins

1. In `gradle/libs.versions.toml`, set `libsignal = "X.Y.Z"`.
2. In `libsignal/source.lock.json`:
   - set `source.tag` to `vX.Y.Z`;
   - set `source.commit` to the full SHA from `git ls-remote https://github.com/signalapp/libsignal refs/tags/vX.Y.Z`. Take the peeled `^{}` SHA if the tag is annotated.
   - set `rust.toolchain` to the contents of libsignal's `rust-toolchain` file at that tag;
   - check `build.ndkRevision`, `build.rustCfg` and `build.features` against libsignal's `java/build_jni.sh` and `.cargo/config.toml` at that tag;
   - check that every `preparation.keep` path still exists at that tag.
3. If the NDK revision changes, also update `ndk:` in `metadata/com.privee.app.yml` and in the matching block of `docs/FDROID.md`.

## 2. Build and test against Maven

```bash
./gradlew test :app:lintDebug :app:assembleDebug
```

This build uses the Maven artifacts. Fix any API changes in `core/signal`, and in `app` where it uses libsignal directly. Then run `SignalClientTest`.

## 3. Build from source locally (Linux or WSL)

```bash
NDK_ROOT=/path/to/android-ndk-r28c bash libsignal/scripts/build-libsignal.sh all
./gradlew -PlibsignalBuiltFromSource -PallowUnpinnedLibsignal :app:assembleRelease
```

`-PallowUnpinnedLibsignal` is for this step only. Never commit or use it in CI.

## 4. Re-pin the `.so` hash from the buildserver image

The hash that matters is the one F-Droid gets, so build in the pinned buildserver image:

```bash
bash scripts/fdroid-rb-docker.sh out
cat out/libsignal/SHA256SUMS
```

Write the `libsignal_jni.so` hash into `build.expectedSha256` in `libsignal/source.lock.json`. You can also open the PR first and take the hash from the Reproducibility workflow's artifacts.

## 5. Scanner and blobs

Run the F-Droid scanner over the pruned checkout (see FDROID_VALIDATION.md). If a new binary or blob appears, add it to `preparation.remove`. Never use `scanignore`.

## 6. PR

- Title: `build(deps): bump libsignal to X.Y.Z`.
- The four required checks must pass. The Reproducibility checks (`Build (replay, release checks)`, `Build (fdroid build)` and `compare`) prove both F-Droid builders agree.
- Mention the matching server or website libsignal version in the PR body.
- Do not change `version.properties` in this PR; releasing is a separate release-bump PR (skill `fdroid-release`).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `verifyLibsignal` fails | `.so` hash differs from the pin | Expected after a bump: re-pin from the buildserver image (step 4) |
| Module fails: tag differs from catalogue | Catalogue and lock disagree | Make both `X.Y.Z` |
| `cargo` fails offline | `fetch` step did not run, or `Cargo.lock` changed | Run `build-libsignal.sh fetch` (or `all`) again |
| `compare` fails on `lib/arm64-v8a/libsignal_jni.so` | Toolchain, NDK or path remapping differs | Compare `PROVENANCE` and `SHA256SUMS` of both artifacts |
| `verify_release_apk.py` fails on natives | A new `.so` was packaged | Remove it or justify it; the allowed list is in the script |
| Website and app cannot talk | libsignal versions differ in a protocol-relevant way | Align versions with the server (cross-repo doc) |

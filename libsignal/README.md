# libsignal built from source

The Maven `org.signal:libsignal-android` AAR contains a prebuilt
`libsignal_jni.so`, which F-Droid does not accept. Release builds therefore
build libsignal from source at the version the app already uses, and package
that instead.

| File | Purpose |
|---|---|
| `source.lock.json` | Pins the libsignal tag and commit, rustup and its checksum, the Rust toolchain, the NDK revision, Cargo features, what to keep from the checkout, and the expected SHA-256 of the library |
| `scripts/build-libsignal.sh` | `fetch` (network: rustup, clone, prune, `cargo fetch`), `build` (offline) or `all` |
| `android/build.gradle.kts` | The `:libsignal:android` module: libsignal's Java/Kotlin sources from the checkout, the built `.so` and the acknowledgments asset |

Everything the script produces goes into the git-ignored `libsignal/build/`:

- `build/source/libsignal` is the pruned checkout.
- `build/output/` holds `jniLibs/arm64-v8a/libsignal_jni.so`,
  `assets/acknowledgments/libsignal.md`, `SHA256SUMS` and `PROVENANCE`.

## How it plugs into the app

The module is included only with the Gradle property
`-PlibsignalBuiltFromSource`, which F-Droid's recipe sets through
`gradleprops`. With it, `:app` excludes the Maven `libsignal-android` and
`libsignal-client` artifacts and depends on `:libsignal:android`. Without it,
debug and release builds use the Maven artifacts, as CI does.

- `verifyLibsignal` runs before every build of the module. It fails unless the
  library matches `build.expectedSha256`. `-PallowUnpinnedLibsignal` skips the
  check for local experiments only.
- The module also fails when the lock's tag differs from `libsignal` in
  `gradle/libs.versions.toml`.

Only `arm64-v8a` is built, so release APKs are ARM64 only.

The build is reproducible: source paths are remapped, the toolchain is pinned,
`SOURCE_DATE_EPOCH` is set, and Cargo runs with `--locked --offline`. The script
also checks the result. It must be an AArch64 ELF with 16 KB-aligned segments
that depends only on `libc`, `libm`, `libdl`, `liblog` and `libz`, with debug
logging compiled out.

## Build locally (Linux or WSL)

Install `cmake curl git make ninja-build protobuf-compiler python3`, plus NDK
`28.2.13676358` (r28c). Then:

```text
NDK_ROOT=/path/to/android-ndk-r28c bash libsignal/scripts/build-libsignal.sh all
./gradlew -PlibsignalBuiltFromSource :app:assembleRelease
```

A full build takes a few minutes. To check reproducibility exactly as F-Droid
does, use `bash scripts/fdroid-rb-docker.sh out` instead (see
[docs/FDROID_VALIDATION.md](../docs/FDROID_VALIDATION.md)).

## Bumping libsignal

1. Update `libsignal` in `gradle/libs.versions.toml`. Then update `source.tag`
   and `source.commit` (`git ls-remote https://github.com/signalapp/libsignal
   refs/tags/vX.Y.Z`) in `source.lock.json`.
2. Set `rust.toolchain` to the contents of libsignal's `rust-toolchain` file at
   that tag. Check `build.ndkRevision` and `build.rustCfg` against libsignal's
   `java/build_jni.sh` and `.cargo/config.toml`, and check that the
   `preparation.keep` paths still exist.
3. Run `build-libsignal.sh all` with `-PallowUnpinnedLibsignal`, then build and
   test the app.
4. Pin the new hash. The value that matters is the one produced in the
   buildserver image, so take it from `out/libsignal/SHA256SUMS` after
   `scripts/fdroid-rb-docker.sh out`, or from the Reproducibility workflow's
   artifacts. Write it into `build.expectedSha256`.
5. Run the scanner over the pruned checkout. If a new binary or blob shows up,
   add it to `preparation.remove`; never use `scanignore`. Then open a PR, and
   the Reproducibility workflow confirms that both F-Droid builders agree.

Bumping the NDK, the toolchain or the Cargo features also changes the hash, so
re-pin it the same way. When the recipe's NDK changes, update `ndk:` in
`metadata/com.privee.app.yml` too.

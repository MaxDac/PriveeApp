# F-Droid metadata and build validation

This procedure validates `metadata/com.privee.app.yml` and the APK it
produces in a clean Linux environment. It does not submit anything to
fdroiddata; see [FDROID.md](FDROID.md) for that.

## Inputs

The build must use the public HTTPS repository and a full commit SHA that has
been pushed. Do not use a local checkout, private fork, unpushed patch, GitHub
secret, keystore or cached build output.

The recipe needs:

- the Android SDK (platform 37, build-tools 36.0.0) and NDK r28c
  (`28.2.13676358`), both installed by fdroidserver;
- the Debian packages `cmake curl git make ninja-build protobuf-compiler
  python3` (the recipe's `sudo` step);
- network access in `prebuild` only, for rustup, the pinned nightly toolchain,
  the libsignal clone and `cargo fetch`. `build` runs Cargo with `--offline`.

Everything else is pinned in [`libsignal/source.lock.json`](../libsignal/source.lock.json):
the libsignal commit, the rustup-init checksum, the toolchain, the NDK revision,
the Cargo features, and the expected SHA-256 of `libsignal_jni.so`.

## Metadata checks

In the fdroiddata checkout that contains `metadata/com.privee.app.yml`:

```bash
fdroid readmeta
fdroid rewritemeta com.privee.app
git diff                     # only formatting changes are acceptable
fdroid lint com.privee.app
fdroid checkupdates --allow-dirty com.privee.app   # once a declaring tag exists
```

Check that every build uses a full commit SHA, and that `versionName` and
`versionCode` match `version.properties` at that commit.

## Build

On a buildserver or in the buildserver container:

```bash
fdroid build --verbose --test --on-server --refresh-scanner com.privee.app:<versionCode>
```

`scripts/fdroid-server-build.sh OUTPUT_DIR` does exactly this for the
checked-out commit in the digest-pinned `fdroidserver:buildserver-trixie`
image, the one fdroiddata's CI uses. The commit must already be pushed to
GitHub. `scripts/fdroid-rb-docker.sh OUTPUT_DIR` replays the same recipe
without fdroidserver (`scripts/fdroid_rb_build.py`); it also copies
`libsignal/build/output` to `OUTPUT_DIR/libsignal`.

The scanner runs after `prebuild` and sees the pruned libsignal checkout under
`libsignal/build/source/libsignal`. It must report no problems. Fix findings by
pruning in `source.lock.json` (`preparation.keep` or `preparation.remove`), never
with `scanignore` or `scandelete`.

## APK acceptance checks

```bash
python3 scripts/verify_release_apk.py \
  --apk <unsigned.apk> \
  --version-name <versionName> --version-code <versionCode> \
  --build-tools "$ANDROID_HOME/build-tools/36.0.0"
```

The verifier checks that:

- the application ID is `com.privee.app` and the version matches;
- the APK is not debuggable;
- the only native libraries are `lib/arm64-v8a/libsignal_jni.so` and
  `lib/arm64-v8a/libandroidx.graphics.path.so`, and `libsignal_jni.so` matches
  the hash pinned in `source.lock.json`;
- no desktop libsignal natives from the Maven `libsignal-client` jar are
  packaged;
- `assets/acknowledgments/libsignal.md`, the notices for libsignal's Rust
  dependencies, is present.

`build-libsignal.sh` also checks the library itself: it must be an AArch64 ELF
with 16 KB-aligned LOAD segments, depend only on `libc`, `libm`, `libdl`,
`liblog` and `libz`, and have logging capped at the info level.

## Reproducible builds

F-Droid publishes the upstream-signed APK named by `Binaries` only if its own
build is identical apart from the signature. fdroidserver downloads the GitHub
asset, checks its certificate against `AllowedAPKSigningKeys`, and runs the
equivalent of `apksigcopier compare`. If they match, it publishes our signed
APK; otherwise it publishes nothing for that version.

To reproduce on a Linux Docker host (committed files only):

```bash
bash scripts/fdroid-rb-docker.sh out                  # replay of the recipe
bash scripts/fdroid-server-build.sh out-fd            # real fdroid build (HEAD must be pushed)
cmp out/unsigned.apk out-fd/unsigned.apk
curl -LO https://github.com/MaxDac/PriveeApp/releases/download/v<version>/Privee-<version>.apk
apksigcopier compare Privee-<version>.apk --unsigned out/unsigned.apk
```

The build path is `/home/vagrant/build/com.privee.app`, as on F-Droid's
buildserver, and `SOURCE_DATE_EPOCH` is the commit time of the source.
`build-libsignal.sh` also remaps source paths (`--remap-path-prefix`,
`-ffile-prefix-map`), so the library does not depend on the checkout path. When
bumping the image digest in `scripts/fdroid-rb-docker.sh`, rerun the
Reproducibility workflow.

To debug a mismatch, run `diffoscope a.apk b.apk` (the Reproducibility workflow
attaches reports) and look at the first differing entry:

| Differing entry | Usual cause | Fix |
| --- | --- | --- |
| `lib/arm64-v8a/libsignal_jni.so` | Toolchain, NDK, Cargo features or path remapping | `verifyLibsignal` fails first; compare `out/libsignal/SHA256SUMS` and `PROVENANCE` with `source.lock.json`. When intentionally changing the build, re-pin `build.expectedSha256` from a buildserver-image build. |
| `classes*.dex` | Different JDK or AGP | Use the same image; check dependency versions |
| `assets/dexopt/baseline.prof*` | Profile ordering | Rebuild on the same side first |
| `META-INF/version-control-info.textproto` | Checkout state | Build a clean checkout |
| ZIP alignment or padding only | APK was realigned or re-signed | Sign with `--alignment-preserved`, never `zipalign -f` |

## References

- [F-Droid build metadata reference](https://f-droid.org/docs/Build_Metadata_Reference/)
- [F-Droid reproducible builds](https://f-droid.org/docs/Reproducible_Builds/)
- [F-Droid inclusion policy](https://f-droid.org/docs/Inclusion_Policy/)
- [`docs/RELEASING.md`](RELEASING.md)

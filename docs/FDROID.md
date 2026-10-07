# F-Droid submission and maintenance

This runbook covers submitting Privee to the official F-Droid repository and
maintaining it after acceptance. Privee follows the same model as
[FPInk](https://github.com/MaxDac/fpink): F-Droid rebuilds each release from
source, and because the build is reproducible it publishes our signed GitHub
APK (`Binaries` plus `AllowedAPKSigningKeys`). Users can therefore move between
the GitHub release and F-Droid without reinstalling.

## What makes Privee F-Droid-ready

| Requirement | How it is met |
|---|---|
| No prebuilt native code | The Maven `libsignal-android` AAR ships prebuilt `libsignal_jni.so`. Release builds replace it with libsignal built from source at the pinned commit. See [`libsignal/README.md`](../libsignal/README.md). Debug builds and CI keep the Maven artifacts. |
| No proprietary dependencies | Notifications use UnifiedPush; there is no Google Play Services, Firebase or tracker. |
| Reproducible build | `scripts/fdroid_rb_build.py` replays the recipe in F-Droid's buildserver image. The Reproducibility workflow also builds each release commit with the real `fdroid build` and requires byte-identical APKs. |
| Listing | `fastlane/metadata/android/en-US/` has the title, descriptions, a 512 px icon and changelogs named by versionCode. |
| Recipe | [`metadata/com.privee.app.yml`](../metadata/com.privee.app.yml) mirrors the fdroiddata recipe. |
| Anti-features | None. The server ([MaxDac/Privee](https://github.com/MaxDac/Privee)) is free software too (AGPL-3.0-only), and users choose which server to connect to. |

Published APKs contain `arm64-v8a` only, because that is the only ABI
libsignal is built for. Besides libsignal, the only native library in the APK
is AndroidX `graphics-path` (`libandroidx.graphics.path.so`, pulled in by
Compose). `scripts/verify_release_apk.py` rejects any other native code.

## The recipe

```yaml
    subdir: app
    sudo:   apt-get install -y cmake curl git make ninja-build protobuf-compiler python3
    gradle: [yes]
    prebuild: bash -x ../libsignal/scripts/build-libsignal.sh fetch
    build:  NDK_ROOT=$$NDK$$ bash -x ../libsignal/scripts/build-libsignal.sh build
    ndk: 28.2.13676358
    gradleprops: [libsignalBuiltFromSource]
```

- `prebuild` runs with network access. It installs the pinned Rust toolchain
  with rustup, clones libsignal at the pinned commit, prunes it to what the
  build reads, and runs `cargo fetch --locked`. F-Droid's scanner then checks
  the tree.
- `build` runs offline (`cargo build --offline --locked`) and writes
  `libsignal/build/output`. The `-PlibsignalBuiltFromSource` Gradle property
  includes the `:libsignal:android` module and swaps it in for the Maven
  artifacts. The `verifyLibsignal` task fails the build unless the library
  matches the hash pinned in `libsignal/source.lock.json`.
- Updates are automatic: `UpdateCheckMode: Tags` with the `^v` release-tag
  pattern, `UpdateCheckData` reading `versionCode` and `versionName` from
  `version.properties`, and `AutoUpdateMode: Version`.
- `AllowedAPKSigningKeys` is the certificate SHA-256 of the release key that
  also signed v0.1.0:
  `ea586e3f2deaf1ff3c507f8eae4ce3363c9f892d787da19d0003817ee58892f9`.

The build block does not depend on the version. The tagged `version.properties`
declares the release, so checkupdates only has to copy the block and change the
version fields.

## Prepare the submission

The first F-Droid version must be a release built by the current Release
workflow. v0.1.0 predates the source build of libsignal, so F-Droid cannot
build it. Before that first release, complete the one-time owner setup in
[RELEASING.md](RELEASING.md#one-time-setup).

1. Fork `https://gitlab.com/fdroid/fdroiddata` to `MaxDac/fdroiddata`, add
   the official repository as `upstream`, and create the branch
   `com.privee.app` from `upstream/master`.
2. Copy `metadata/com.privee.app.yml` from this repository at the release tag.
   Then point it at the release:

   ```text
   python3 scripts/fdroid_mr_bump.py --tag v<version> --commit-style sha \
     --metadata ../fdroiddata/metadata/com.privee.app.yml
   ```

   The fork uses the full commit SHA; the mirror here shows the tag.
3. Do not copy Fastlane files, screenshots or APKs into fdroiddata. F-Droid
   reads the listing from this repository.
4. Validate on Linux with fdroidserver:

   ```text
   fdroid readmeta
   fdroid rewritemeta com.privee.app
   fdroid lint com.privee.app
   fdroid build --verbose --test com.privee.app:<versionCode>
   ```

   [FDROID_VALIDATION.md](FDROID_VALIDATION.md) shows how to run the same
   build in the buildserver image with Docker. Commit with the message
   `New app: Privee`, push, and let the fork's GitLab CI pass.
5. Open a merge request from `MaxDac/fdroiddata:com.privee.app` to
   `fdroid/fdroiddata:master` titled **New app: Privee**. Include:
   - the upstream repository, release tag, full commit SHA, versionName and
     versionCode;
   - the validation commands and their results;
   - that libsignal (Rust, AGPL-3.0-only) is built from source at a pinned
     commit with a pinned toolchain, and that the Maven AAR's prebuilt `.so`
     is not used;
   - the ARM64-only build, and that the app talks only to a Privee server the
     user chooses, with UnifiedPush for notifications;
   - the reproducible-build setup (`Binaries`, `AllowedAPKSigningKeys`) with a
     link to the Reproducibility workflow run for the release commit.

   Do not claim anything the evidence doesn't show.

## Reviewer-response loop

- For metadata-only changes, edit the fork branch, rerun `rewritemeta` and
  `lint`, and reply with the new commit and the results.
- If the source or the build has to change, change this repository first and
  cut a new release. The `fdroid-mr` job (see below) then points the MR at the
  new tag. Never move a tag or replace a release asset.
- Never hide scanner findings with broad `scanignore`/`scandelete` entries. If
  the scanner flags something in the libsignal checkout, prune it in
  `libsignal/source.lock.json` (`preparation.keep` or `preparation.remove`).

## While the merge request is open

checkupdates only runs for merged apps, so until the merge every new release
must be pushed to the MR. `scripts/fdroid_mr_bump.py` rewrites the single build
block and `CurrentVersion`/`CurrentVersionCode` for a release tag. It reads
`version.properties` at the tag, and rejects a mismatched tag, an empty
changelog, or a versionCode that doesn't supersede the current one. With
`--commit-style sha` it first replaces the fork's build entry with the mirror's
entry at the tag, so build-step changes follow the source. Change build steps in
the mirror, not in the fork.

### Automatic: the Release workflow

When a publishing run succeeds, the `fdroid-mr` job in `release.yml` runs the
script for the tag that run published. It pushes a commit to `com.privee.app`
on [`MaxDac/fdroiddata`](https://gitlab.com/MaxDac/fdroiddata), whose GitLab
CI runs lint and build. The job summary shows the line to post as an MR
comment.

The job needs the repository secret `FDROIDDATA_DEPLOY_KEY`. It is the private
half of an SSH [deploy key](https://docs.gitlab.com/user/project/deploy_keys/)
that can push only to the fork:

```text
ssh-keygen -t ed25519 -N "" -C privee-release -f fdroiddata_deploy
# GitLab: MaxDac/fdroiddata > Settings > Repository > Deploy keys > Add new key
#   paste fdroiddata_deploy.pub, tick "Grant write permissions to this key"
gh secret set FDROIDDATA_DEPLOY_KEY -R MaxDac/PriveeApp < fdroiddata_deploy
rm fdroiddata_deploy fdroiddata_deploy.pub
```

The job pins GitLab's ed25519 host key and never force-pushes. If the secret is
missing, the job only warns, and you update the MR by hand. The variables
`FDROIDDATA_FORK` and `FDROID_MR_BRANCH` override the fork and the branch. Once
the MR is merged, delete the job, the secret and the deploy key.

### Manual fallback and the mirror

```text
git fetch --tags origin
python3 scripts/fdroid_mr_bump.py --tag latest
python3 scripts/fdroid_mr_bump.py --tag latest --commit-style sha \
  --metadata ../fdroiddata/metadata/com.privee.app.yml
```

`--tag latest` picks the release tag with the highest versionCode. Only tags
that match `UpdateCheckMode` and declare themselves in `version.properties`
count. Commit the mirror change in a PR here. For the fork, run
`fdroid rewritemeta` and `fdroid lint` before pushing, then post the
`MR note` line as a comment on the MR.

## After merge

Watch the first official build and index cycle. Check the build log, scanner
output, package ID, version, ARM64 support, the listing, and that F-Droid
published our signed APK (reproducible-build verification passed). If the build
does not reproduce, compare F-Droid's unsigned APK with ours using diffoscope,
fix the source, and publish a new release; never replace a GitHub asset.

For every later release: declare it in a release-bump PR, run the Release
workflow ([RELEASING.md](RELEASING.md)), and let checkupdates pick up the tag.

## References

- [F-Droid submission quick start](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
- [Build metadata reference](https://f-droid.org/docs/Build_Metadata_Reference/)
- [Reproducible builds](https://f-droid.org/docs/Reproducible_Builds/)
- [Inclusion policy](https://f-droid.org/docs/Inclusion_Policy/)

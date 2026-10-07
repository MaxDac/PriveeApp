# Releases

Privee releases are **manual only**. CI runs on pushes and pull requests but
never publishes. The Release workflow has no push, tag, schedule or
pull-request trigger.

Every release is a reproducible build (see below). F-Droid rebuilds the same
commit and, if its APK is identical, publishes our signed APK. See
[FDROID.md](FDROID.md).

## One-time setup

1. **Signing secrets.** The workflow uses the repository secrets that already
   sign v0.1.0. Back up the keystore, alias and passwords outside GitHub. Never
   use the debug key, never generate a new key per release, and never commit
   the keystore.

   | Secret | Value |
   |---|---|
   | `KEYSTORE_BASE64` | Single-line base64 of the keystore |
   | `KEYSTORE_PASSWORD` | Keystore password |
   | `KEY_ALIAS` | Key alias |
   | `KEY_PASSWORD` | Key password |

   You can move them into the `release` environment (step 2) so that only the
   signing job can read them.
2. **`release` environment.** Create a GitHub environment named `release`,
   restrict its deployment branches to `main`, and add required reviewers if
   your plan supports them. Set two environment **variables** (not secrets):

   | Variable | Value |
   |---|---|
   | `RELEASE_PUBLICATION_APPROVED` | `true` (publishing fails without it) |
   | `RELEASE_CERTIFICATE_SHA256` | `ea586e3f2deaf1ff3c507f8eae4ce3363c9f892d787da19d0003817ee58892f9` |

   The workflow checks the signed APK against the certificate and against every
   earlier release manifest. Key rotation is not supported. F-Droid's
   `AllowedAPKSigningKeys` pins the same certificate.
3. **Backfill v0.1.0.** The release tooling derives versions and the signing
   history from the `release-manifest.json` of every published release, and
   fails closed when one is missing. v0.1.0 was published before this tooling,
   so add its manifest once and give the APK the name the tooling expects:

   ```json
   {
     "schemaVersion": 1,
     "applicationId": "com.privee.app",
     "versionName": "0.1.0",
     "versionCode": 1,
     "tag": "v0.1.0",
     "sourceSha": "aa472e3d128fe9e01800b9420ef43b73831e8fd6",
     "apk": "Privee-0.1.0.apk",
     "sha256": "8b7db744cb43dd2d6adbbad6ee16614cc3410eb33ff3697c2b151eb8b3f8439e",
     "signingCertificateSha256": "ea586e3f2deaf1ff3c507f8eae4ce3363c9f892d787da19d0003817ee58892f9"
   }
   ```

   ```text
   gh release upload v0.1.0 release-manifest.json -R MaxDac/PriveeApp
   gh api repos/MaxDac/PriveeApp/releases/tags/v0.1.0 --jq '.assets[] | "\(.id) \(.name)"'
   gh api -X PATCH repos/MaxDac/PriveeApp/releases/assets/<apk-asset-id> -f name=Privee-0.1.0.apk
   gh release delete-asset v0.1.0 privee-v0.1.0.apk.sha256 -R MaxDac/PriveeApp   # optional
   ```

   Every value above was checked against the published APK, its signature and
   the tag. Run `python3 scripts/release_version.py --repository MaxDac/PriveeApp
   --release-type stable` afterwards; it should resolve `0.2.0` with versionCode
   `2`.
4. **F-Droid MR automation (optional).** Add the `FDROIDDATA_DEPLOY_KEY`
   secret described in [FDROID.md](FDROID.md#automatic-the-release-workflow).

None of this is created by the source change itself. Configuring the
environment is the owner's approval to publish.

## Run a release

To have an agent do all of this, prompt Claude Code or Copilot CLI with "Use
the fdroid-release skill to cut the next release". The skill is in
`.claude/skills/fdroid-release/SKILL.md`. It runs the steps below and then
updates the F-Droid MR and the recipe mirror.

First declare the release in a release-bump PR. F-Droid reads the version from
`version.properties` at the release tag, so the tagged commit must already
contain it:

```text
git switch -c release/next origin/main
python3 scripts/release_version.py --repository MaxDac/PriveeApp --release-type stable --prepare
# write fastlane/metadata/android/en-US/changelogs/<versionCode>.txt (max 500 characters)
git commit -am "Release <versionName>" && gh pr create --fill
```

`--prepare` computes the next version the same way the workflow does (pass
`--requested-version` or `--release-type prerelease` if needed), writes it to
`version.properties`, and creates an empty changelog. CI rejects a declared
release whose changelog is missing, empty or too long. Merge the PR. The merge
triggers the Reproducibility workflow on `main`; wait for it to pass. Then go to
**Actions > Release > Run workflow** and choose `main`. The workflow stops
unless the version it computes equals the declared one. It never commits to
`main`.

- **release_type**: `prerelease` (the workflow default) or `stable`.
  Pre-releases are marked as such on GitHub and never become the Latest
  release. F-Droid ships both, since the update pattern matches every `v` tag.
- **version**: leave it blank to choose automatically. A stable release takes
  the next minor (after `0.1.0`, `0.2.0`). A pre-release appends `-preview.1`
  to that base, or advances the highest existing suffix (`preview.9` becomes
  `preview.10`). An explicit version must match the type and be newer than
  every published version.
- **publish**: leave it unchecked for a rehearsal. A rehearsal builds an
  unsigned APK artifact, uses no secrets, and creates no tag or release.
  Check it to build, wait for environment approval, sign and publish.

```text
gh workflow run release.yml --ref main -f release_type=stable -F publish=false
gh workflow run release.yml --ref main -f release_type=stable -F publish=true
```

The build is tied to the commit that was dispatched. A concurrency group stops
release runs from overlapping.

While the F-Droid merge request is open, the `fdroid-mr` job pushes each new
tag to it. See [FDROID.md](FDROID.md#while-the-merge-request-is-open).

## Versioning

`version.properties` declares the release built from each commit. Release
builds read it directly, so F-Droid and local builds of a tag produce the same
version. `-PreleaseVersionName` and `-PreleaseVersionCode` are for local
experiments only. The versionCode is one more than the highest published code,
up to `2100000000`.

Every published release must have a valid `release-manifest.json`. Missing
manifests, changed tag targets, inconsistent certificates, or an existing target
tag or draft all fail closed. Do not publish APK releases outside this workflow.

## Release assets

| File | Purpose |
|---|---|
| `Privee-<version>.apk` | Signed APK. The name is part of F-Droid's `Binaries` URL; never rename it. |
| `release-manifest.json` | Application ID, version, source SHA, tag, APK name and hash, certificate SHA-256 |
| `SHA256SUMS` | Checksums of the APK, manifest and licence |
| `LICENSE` | The AGPL-3.0 text |

The APK contains `arm64-v8a` native code only, because libsignal is built from
source for that ABI alone. 32-bit ARM and x86 devices cannot install release
APKs; use a debug build there.

Only the signing job has write permission, and it runs no repository build
scripts. It receives the unsigned APK and restores the keystore into a temporary
directory. It signs in place with `apksigner sign --alignment-preserved` (v2 and
v3 signatures), then verifies the signer and the APK identity. `apksigcopier
compare` must confirm that the signed APK is the unsigned build plus a
signature. The keystore is deleted even when signing fails. Realigning the APK
would break F-Droid's reproducibility check.

Publishing creates the tag and a draft release, uploads every asset, downloads
them again to compare hashes, and only then publishes. Existing tags and
releases are never overwritten.

## Reproducible builds

`scripts/fdroid-rb-docker.sh` replays the recipe's last build block (via
`scripts/fdroid_rb_build.py`) inside the digest-pinned
`registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie` image, as
`fdroid build --on-server` would. It uses the same apt packages, OpenJDK 21,
gradlew-fdroid, NDK, `/home/vagrant/build/com.privee.app` path,
`SOURCE_DATE_EPOCH`, prebuild and build steps (the libsignal source build), and
Gradle invocation. On a Linux Docker host:

```text
bash scripts/fdroid-rb-docker.sh out        # builds HEAD (committed files only)
bash scripts/fdroid-server-build.sh out-fd  # the real fdroid build (HEAD must be pushed)
cmp out/unsigned.apk out-fd/unsigned.apk
```

The **Reproducibility** workflow runs both builders on pull requests that
change build inputs (including `libsignal/**`), and fails unless the APKs are
identical. When they differ, it attaches diffoscope reports. The replay build
also runs `:app:lintRelease` after copying out its APK, and fails if that
changes the APK.

The workflow also runs on pushes to `main` that change `version.properties`,
and on manual runs on `main`. Those runs upload the verified APK as
`verified-release-<sha>` (kept 30 days). The Release workflow reuses that
artifact when a successful Reproducibility run and a successful CI run exist for
the same commit on `main`. Otherwise it rebuilds the APK and runs the unit tests
and release checks itself. The release summary says which path it took.

The libsignal library must also match the hash pinned in
`libsignal/source.lock.json`. When bumping libsignal, the toolchain or the NDK,
follow [`libsignal/README.md`](../libsignal/README.md).

## Build and validate locally

Debug builds and CI use the Maven libsignal artifacts and work on any OS with
JDK 17 or 21 and Android SDK platform 37:

```text
./gradlew test lintDebug assembleDebug
```

A release APK identical to the published one needs the libsignal source build,
which runs on Linux (or WSL) only. Docker is the easiest way:
`bash scripts/fdroid-rb-docker.sh out`. To build without Docker, install the
recipe's apt packages and NDK `28.2.13676358`, then:

```text
NDK_ROOT=<ndk-r28c> bash libsignal/scripts/build-libsignal.sh all
./gradlew -PlibsignalBuiltFromSource :app:lintRelease :app:assembleRelease
python3 scripts/verify_release_apk.py \
  --apk app/build/outputs/apk/release/app-release-unsigned.apk \
  --version-name <versionName> --version-code <versionCode> \
  --build-tools "$ANDROID_HOME/build-tools/36.0.0"
python3 -m unittest discover -s scripts/tests
```

A release build without `-PlibsignalBuiltFromSource` packages the prebuilt
Maven library and fails `verify_release_apk.py`. Never publish one.

## Failure and recovery

- **Before tagging:** fix the problem and dispatch again. A rehearsal does not
  use up a versionCode.
- **Existing tag or draft:** inspect the earlier run, the tag commit, the
  manifest, the certificate and the uploaded bytes. The workflow stops instead
  of guessing how to resume. Finish a verified complete draft, or remove an
  unpublished failed draft or tag, before dispatching again. Never remove or
  move a published version.
- **After publication:** check that the release is public and every asset
  downloads. Correct mistakes with a new version, never by replacing an APK or
  reusing a versionCode.
- **Lost signing key:** restore the backup. A new key cannot update existing
  installations in place, from GitHub or from F-Droid.
- **Missing manifest:** rebuild it from the real APK, tag and signature after
  owner review, as was done for v0.1.0. Never guess a versionCode or a
  certificate.

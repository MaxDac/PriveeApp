---
name: fdroid-release
description: Publish Privee (com.privee.app) on F-Droid and keep it updated. Covers the one-time owner setup, the first submission ("New app: Privee" merge request on fdroid/fdroiddata), every later release (release-bump PR, Release workflow, signed GitHub release, fdroiddata MR/mirror update), the reviewer-response loop and the post-merge checks. Use when asked to release, cut a stable or preview version, submit or ship to F-Droid, update the F-Droid MR or the GitLab fork branch, answer an F-Droid reviewer, or debug a failing reproducible build.
---

# Privee on F-Droid

Privee ships to F-Droid the same way FPInk does. F-Droid rebuilds every release
from source in its buildserver image. Because that build is byte-identical to
ours, F-Droid publishes **our** signed GitHub APK (`Binaries` plus
`AllowedAPKSigningKeys`).

Background reading, if a step fails:
- [`docs/FDROID.md`](../../../docs/FDROID.md): recipe, submission, MR loop, after merge.
- [`docs/RELEASING.md`](../../../docs/RELEASING.md): Release workflow, one-time setup, recovery.
- [`docs/FDROID_VALIDATION.md`](../../../docs/FDROID_VALIDATION.md): local and buildserver builds, diffoscope.
- [`libsignal/README.md`](../../../libsignal/README.md): source build of `libsignal_jni.so`, re-pinning.
- [`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md) and [`docs/TECHNOLOGIES.md`](../../../docs/TECHNOLOGIES.md): what changes affect reproducibility, and the stack.
- Related skills: `libsignal-upgrade`, `dependency-upgrade`, `store-screenshots` (refresh store images before a release), `cross-repo-change` (server changes must be deployed before the app release that needs them).

This file exists twice and the two copies must stay byte-identical:
- `.claude/skills/fdroid-release/SKILL.md` (Claude Code);
- `.github/skills/fdroid-release/SKILL.md` (GitHub Copilot).

`scripts/tests/test_skill_copies.py` enforces this. Edit one copy, then copy it over the other.

## Choose the mode

Decide which mode applies from the prompt and the live state. Never decide from memory.

```bash
gh release list -R MaxDac/PriveeApp --limit 5
gh api repos/MaxDac/PriveeApp/environments --jq '.environments[].name'      # expect "release"
git ls-remote https://gitlab.com/MaxDac/fdroiddata.git refs/heads/com.privee.app       # fork branch exists?
curl -fsI https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/com.privee.app.yml >/dev/null && echo merged
```

| State | Mode |
|---|---|
| No `release` environment, or v0.1.0 has no `release-manifest.json` | **A. One-time setup**, then B |
| Setup done, no `com.privee.app` branch on the fork | **B. First submission** |
| MR open, a new version is wanted | **C. Release** (all steps) |
| MR open, reviewer commented | **D. Reviewer loop** |
| `metadata/com.privee.app.yml` exists on `fdroid/fdroiddata` master | **C. Release** without C4–C6, then **E. After merge** |
| A Reproducibility or Release run failed | **F. Failure playbook** |

Inputs, taken from the prompt:
- **type**: `stable` (default) or `prerelease`.
- **version**: optional. Leave it out so the tooling picks the next version.
- **post**: whether to post comments on GitLab. Default: draft them and hand them to the user.

Report every PR, run, tag, release, commit and MR you create, as links.

## Guardrails

- Never move, delete or replace a tag, a release or a release APK. Never reuse a
  versionCode. To fix a bad release, publish a new one.
- Never force-push to `main`, to `fdroid/fdroiddata`, or to the fork branch
  `com.privee.app`. The only fork branch you push to is `com.privee.app`.
- Never bypass required checks (`gh pr merge --admin`) or ruleset rules. Never
  edit `version.properties` by hand: `release_version.py --prepare` writes it.
- Never commit a keystore, a password or a deploy key. Never print secrets.
- Never paper over F-Droid scanner findings with broad `scanignore`/`scandelete`.
  Prune the libsignal checkout in `libsignal/source.lock.json` instead.
- Change build steps only in this repository's mirror, `metadata/com.privee.app.yml`.
  The fork copies them from the mirror at the release tag.
- In MRs and comments, never claim anything the evidence doesn't show.
- If a check or run fails, stop and show the failing log
  (`gh run view <id> --log-failed`). Then go to F. Do not retry by publishing
  another version.

## Environment notes

- Linux tools (`python3`, `fdroid`, the GitLab SSH key) live in WSL on the
  owner's Windows machine.
- WSL git cannot operate on a Windows worktree whose `.git` file points to a
  Windows path. Run repository git commands from Windows, and run Python
  scripts and fdroidserver in WSL (`wsl -e bash -lc '…'`).
- The fdroiddata clone is `~/fdroiddata` in WSL, with remotes `origin` =
  `git@gitlab.com:MaxDac/fdroiddata.git` and `upstream` =
  `https://gitlab.com/fdroid/fdroiddata.git`. It also carries FPInk's branch
  `com.fpink.capture`; never touch that branch.
- `glab` may not be installed. Without an authenticated `glab` or a GitLab
  token, you cannot create MRs or comments on GitLab. Produce the MR link and
  text for the user instead.
- `git push` to GitHub can return transient `500 Internal Server Error`. Retry
  with a back-off for a few minutes before trying anything else.

## A. One-time setup

Skip each step that is already done. Check the state first.

1. **Signing secrets.** The repository secrets `KEYSTORE_BASE64`,
   `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` must exist (`gh secret
   list`). They hold the key that signed v0.1.0. Never create a new key.
2. **`release` environment** with two variables:

   ```bash
   gh api -X PUT repos/MaxDac/PriveeApp/environments/release \
     --input - <<'JSON'
   {"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}
   JSON
   gh api -X POST repos/MaxDac/PriveeApp/environments/release/deployment-branch-policies -f name=main -f type=branch
   gh variable set RELEASE_PUBLICATION_APPROVED -R MaxDac/PriveeApp --env release --body true
   gh variable set RELEASE_CERTIFICATE_SHA256 -R MaxDac/PriveeApp --env release \
     --body ea586e3f2deaf1ff3c507f8eae4ce3363c9f892d787da19d0003817ee58892f9
   ```

   `RELEASE_PUBLICATION_APPROVED=true` records that the owner reviewed
   licensing. Set it only when the user asked for the publication.
3. **Backfill v0.1.0.** Upload the manifest from
   [`docs/RELEASING.md`](../../../docs/RELEASING.md#one-time-setup), and rename
   (do not replace) the APK asset to `Privee-0.1.0.apk`. Then verify:

   ```bash
   gh release download v0.1.0 -R MaxDac/PriveeApp -p Privee-0.1.0.apk -D /tmp/v010
   sha256sum /tmp/v010/Privee-0.1.0.apk        # must equal the manifest's sha256
   python3 scripts/release_version.py --repository MaxDac/PriveeApp --release-type stable
   ```

   The last command must resolve `0.2.0` with versionCode `2`.
4. **Fork deploy key** (optional; it enables the automatic `fdroid-mr` job).
   Generate an ed25519 key in a temp dir and add its public half to
   `MaxDac/fdroiddata` as a deploy key **with write access**. This needs
   GitLab: the UI, `glab`, or `POST /projects/:id/deploy_keys` with a token.
   Store the private half with `gh secret set FDROIDDATA_DEPLOY_KEY < key`,
   then delete both files. Without a GitLab credential, leave this step to
   the user and use the manual path in C4.
5. **Listing.** `fastlane/metadata/android/en-US/` must have `title.txt`,
   `short_description.txt` (≤ 80 chars), `full_description.txt`,
   `images/icon.png` (512 px) and `images/phoneScreenshots/*.png`.

   To take screenshots, use the `Pixel_7` AVD and a debug build:
   - `adb exec-out screencap -p > N.png`;
   - number the files `1.png`, `2.png` and so on;
   - show real screens only (onboarding, conversation list, a conversation, settings);
   - include no personal data;
   - status bar in demo mode: `adb shell settings put global sysui_demo_allowed 1`, then the `demo` broadcasts.

## B. First submission

The first F-Droid version must be a release built by the current Release
workflow. v0.1.0 cannot be built by F-Droid, because it predates the libsignal
source build.

1. **Release it.** Run C1–C3 (normally `0.2.0`, versionCode `2`).
2. **Evidence.** For the release commit `$SHA`:

   ```bash
   gh run list -R MaxDac/PriveeApp --workflow reproducibility.yml --commit "$SHA" \
     --json databaseId,conclusion,url --jq '.[0]'           # must be success
   gh release view "v$V" -R MaxDac/PriveeApp --json url,assets
   ```

   Record the following:
   - the Reproducibility run URL;
   - the unsigned APK SHA-256 from its summary;
   - `libsignal_jni.so` SHA-256, which equals `build.expectedSha256` in `libsignal/source.lock.json`;
   - the release URL;
   - the signed APK SHA-256 from `release-manifest.json`.
3. **Fork branch** (WSL):

   ```bash
   cd ~/fdroiddata
   git fetch upstream master && git fetch origin
   git switch -c com.privee.app upstream/master
   cp /mnt/c/…/PriveeApp/metadata/com.privee.app.yml metadata/       # mirror at the tag
   python3 /mnt/c/…/PriveeApp/scripts/fdroid_mr_bump.py --tag "v$V" --commit-style sha \
     --source /mnt/c/…/PriveeApp --metadata metadata/com.privee.app.yml
   fdroid readmeta && fdroid rewritemeta com.privee.app && fdroid lint com.privee.app
   git diff --stat upstream/master       # exactly one new file: metadata/com.privee.app.yml
   git add metadata/com.privee.app.yml && git commit -m "New app: Privee"
   git push -u origin com.privee.app
   ```

   Copy the mirror from the tag (`git show "v$V:metadata/com.privee.app.yml"`),
   not from a working tree that may be ahead of it. In the fork, `commit:`
   must be the full SHA; the mirror uses the tag. Do not copy Fastlane files,
   screenshots or APKs into fdroiddata.

   An optional local build is `fdroid build --verbose --test com.privee.app:<code>`.
   It takes about 30 minutes and needs the Android SDK/NDK set up for
   fdroidserver. The Reproducibility run already ran the real `fdroid build`
   in the pinned buildserver image, so cite that run instead of skipping
   evidence.
4. **Fork CI.** Wait for the pipeline on `MaxDac/fdroiddata@com.privee.app` to
   pass (`https://gitlab.com/MaxDac/fdroiddata/-/pipelines?ref=com.privee.app`).
   If `glab` is unavailable, give the link to the user.
5. **Open the MR** from `MaxDac/fdroiddata:com.privee.app` into
   `fdroid/fdroiddata:master`, titled **New app: Privee**. Use fdroiddata's
   "App inclusion" template checklist, and include:
   - Upstream: https://github.com/MaxDac/PriveeApp, tag `v$V`, commit `$SHA`, versionName, versionCode.
   - License: AGPL-3.0-only (app); server MaxDac/Privee, AGPL-3.0-only, self-hostable, chosen by the user.
   - Native code: libsignal v0.86.5 (Rust, AGPL-3.0-only) is built from source at a pinned commit, with a pinned nightly toolchain and NDK r28c. The prebuilt `.so` from the Maven AAR is never used. The pinned output hash is enforced by `verifyLibsignal`. AndroidX `graphics-path` is the only other native library.
   - ABI: `arm64-v8a` only, because libsignal is built for that target only.
   - Network: only the server the user enters. Notifications via UnifiedPush; no Google services, Firebase or trackers. Anti-features: none.
   - Reproducible builds: `Binaries` and `AllowedAPKSigningKeys` (`ea586e3f…92f9`). Link the Reproducibility run where the real `fdroid build` and the recipe replay produced identical unsigned APKs, and give their SHA-256.
   - Validation commands run, each with its result.

   With `glab`:
   `glab mr create -R MaxDac/fdroiddata --source-branch com.privee.app --target-branch master --target-project fdroid/fdroiddata --title "New app: Privee" --description "$(cat mr.md)"`.
   Otherwise give the user this URL plus the text:
   `https://gitlab.com/MaxDac/fdroiddata/-/merge_requests/new?merge_request[source_branch]=com.privee.app&merge_request[target_project_id]=36528`.
   36528 is the project ID of `fdroid/fdroiddata`; check it with
   `curl -s https://gitlab.com/api/v4/projects/fdroid%2Ffdroiddata | jq .id`.
6. **Record the MR number** in the PR or issue that tracks the submission, so
   C6 and D can find it.

## C. Release (every version)

### C1. Preflight

```bash
gh auth status
git fetch origin --tags --prune
git switch --detach origin/main
gh run list --branch main --workflow ci.yml --limit 1 --json conclusion,headSha   # success at origin/main
git tag --sort=-v:refname | head -3
git log --oneline "$(git tag --sort=-v:refname | head -1)"..origin/main           # what ships
```

If nothing user-visible changed since the last tag, say so and ask before continuing.

### C2. Release-bump PR

```bash
python3 scripts/release_version.py --repository MaxDac/PriveeApp --release-type <type> [--requested-version <version>] --prepare
```

The command does two things:
- prints the tag and versionCode;
- writes `version.properties` and an empty `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

Write that changelog for end users:
- at most 500 characters of plain text, all of it true;
- summarise the user-visible features and fixes;
- mention dependency updates only as a group;
- leave out CI, docs and tooling changes.

```bash
V=<versionName>; git switch -c "release/$V"
git add version.properties fastlane/metadata/android/en-US/changelogs/
git commit -m "chore(release): $V"
git push -u origin HEAD
gh pr create --title "chore(release): $V" --body "Declare $V (versionCode <code>) for the Release workflow and F-Droid auto-update."
gh pr merge --squash --auto --delete-branch
gh pr checks --watch --required --interval 60
until [ "$(gh pr view --json state -q .state)" = MERGED ]; do sleep 30; done
```

Use the PR template in `.github/pull_request_template.md` for the body when
it asks for more. Required checks on `main`:
- `Android (test, lint, assemble)`;
- `Build (replay, release checks)`;
- `Build (fdroid build)`;
- `compare`.

The Reproducibility builds take about 15 minutes. Rebase if `main` moves.

### C3. Publish

```bash
git fetch origin; MAIN=$(git rev-parse origin/main)
gh workflow run release.yml --ref main -f release_type=<type> [-f version=$V] -F publish=true
sleep 15; RUN=$(gh run list --workflow release.yml --limit 1 --json databaseId,headSha -q ".[] | select(.headSha==\"$MAIN\") | .databaseId")
gh run watch "$RUN" --exit-status --interval 60
gh release view "v$V" --json tagName,isPrerelease,assets -q '{tagName,isPrerelease,assets:[.assets[].name]}'
```

Check all of the following:
- the release has `Privee-$V.apk`, `SHA256SUMS`, `release-manifest.json` and `LICENSE`;
- `git rev-parse "v$V^{commit}"` equals `$MAIN`;
- the manifest's `signingCertificateSha256` is `ea586e3f…92f9`.

The workflow reuses the `verified-release-<sha>` artifact from the
Reproducibility run on `main` when one exists. Otherwise it rebuilds.

### C4. Point the fdroiddata MR at the release (only while the MR is open)

With `FDROIDDATA_DEPLOY_KEY` set, the `fdroid-mr` job of the same run does this:

```bash
gh run view "$RUN" --json jobs -q '.jobs[] | select(.name=="fdroid-mr") | .conclusion'
git ls-remote https://gitlab.com/MaxDac/fdroiddata.git refs/heads/com.privee.app
```

If the job only warned (no key), do it by hand in WSL:

```bash
cd ~/fdroiddata && git fetch origin && git switch com.privee.app && git merge --ff-only origin/com.privee.app
python3 /mnt/c/…/PriveeApp/scripts/fdroid_mr_bump.py --tag "v$V" --commit-style sha \
  --source /mnt/c/…/PriveeApp --metadata metadata/com.privee.app.yml
fdroid rewritemeta com.privee.app && fdroid lint com.privee.app
git diff --stat      # only metadata/com.privee.app.yml: version lines plus any mirrored build-step change
git commit -qam "Privee: update to v$V" && git push origin com.privee.app
```

`--source` must be a checkout that already has the tag (`git fetch --tags`).
Keep the `MR note:` line the script prints.

### C5. Update the in-repo mirror

```bash
git switch -c "fdroid-mirror/$V" origin/main
python3 scripts/fdroid_mr_bump.py --tag latest      # must resolve to v$V
git commit -am "chore(fdroid): point mirror recipe at v$V" && git push -u origin HEAD
gh pr create --fill && gh pr merge --squash --auto --delete-branch
```

### C6. Tell the reviewer (only while the MR is open)

Draft a comment for the MR:

```text
Updated to v<V> (commit <full SHA>, versionCode <code>). <One sentence on what changed.>
The build block, CurrentVersion and CurrentVersionCode were rewritten; rewritemeta and lint pass.
Reproducibility run: <url>
```

Post it only if `post` is set and `glab` is authenticated:
`glab mr note <MR> -R fdroid/fdroiddata -m "…"`. Otherwise hand the draft to the user.

## D. Reviewer-response loop

1. Read every unresolved thread on the MR (`glab mr view <MR> -R fdroid/fdroiddata --comments`,
   or ask the user to paste them). Classify each one:
   - **Metadata only** (recipe fields, categories, description, formatting):
     edit the mirror here **and** the fork branch. Rerun
     `fdroid rewritemeta` and `fdroid lint`, push the fork, and open a PR here
     for the mirror.
   - **Build or source change** (scanner finding, a prebuilt in a dependency,
     a toolchain change): fix it in this repository on a PR, and get all
     four required checks green. Then cut a new release (C) so the fork
     points at a tag that contains the fix. Never point the fork at an
     untagged commit.
   - **Policy question** (anti-features, network use, license): answer it
     with facts from the source and docs, and link the files.
2. Reply to each thread with what changed, the fork commit, and the evidence.
   Do not resolve a reviewer's thread yourself unless they asked you to.
3. Repeat until the MR is approved and merged.

## E. After merge

1. F-Droid's checkupdates now picks up new `v*` tags whose `version.properties`
   declares the release (`UpdateCheckMode: Tags`, `AutoUpdateMode: Version`).
   Releases follow C1–C3 and C5 only.
2. Watch the first official build:
   - https://monitor.f-droid.org/builds (search `com.privee.app`);
   - the app page `https://f-droid.org/packages/com.privee.app/` after the next index.

   Check the following:
   - the scanner output is clean;
   - the version and ABI (arm64) are right;
   - the listing text, icon and screenshots are right;
   - the published APK is **ours**: its signer certificate SHA-256 is `ea586e3f…92f9` (`apksigner verify --print-certs`).
3. If F-Droid's build does not reproduce, get F-Droid's unsigned APK and
   diffoscope it against ours
   ([`docs/FDROID_VALIDATION.md`](../../../docs/FDROID_VALIDATION.md)). Fix
   the source and publish a new release.
4. Clean up the MR-only automation:
   - delete the `fdroid-mr` job from `release.yml`;
   - delete the `FDROIDDATA_DEPLOY_KEY` secret and its GitLab deploy key;
   - delete C4 and C6 from this skill (in both copies).

## F. Failure playbook

Read the failing step first: `gh run view <id> --log-failed | grep -E "error|FAILURE|What went wrong" | tail -n 40`.

| Symptom | Cause and fix |
|---|---|
| `unknown proxy name` from rustup | rustup-init dispatches on argv[0]. Download it to a file named exactly `rustup-init` (`build-libsignal.sh install_rust`). |
| `linker 'cc' not found` | The buildserver image has no host C toolchain. Keep `build-essential` in the recipe's `sudo` apt line. |
| `Task 'assembleTrueRelease' not found` | YAML read an unquoted `yes` as a boolean. The recipe must say `gradle: ['yes']`. |
| `Cannot find a Java installation … languageVersion=17` | The image ships only JDK 21. Don't use `jvmToolchain(N)`. Set `jvmTarget`/`sourceCompatibility` instead. |
| `Expected exactly one unsigned release APK` | The output path changed (flavors or splits). Fix the glob in `scripts/fdroid_rb_build.py`. |
| `verifyLibsignal` hash mismatch | The image, toolchain or source changed. Rebuild in the image (`docs/FDROID_VALIDATION.md`). Re-pin `build.expectedSha256` in `libsignal/source.lock.json` only when the change is intended and explained in the PR. |
| `compare` fails (APKs differ) | Download both `unsigned.apk` artifacts and run diffoscope. Typical causes are timestamps, build paths, file ordering and non-pinned tools. Fix the source; never compare against a modified APK. |
| Release run fails on the certificate or `release-manifest` | Check the `release` environment variables and the v0.1.0 backfill (A2–A3). Never re-sign with another key. |
| Scanner flags a file in `libsignal/build` | Add it to `preparation.remove`, or narrow `preparation.keep`, in `libsignal/source.lock.json`. Then re-run. |
| `git push` 500 errors | A transient GitHub problem. Retry with a back-off. The REST API (`gh api`) usually still works. |

After any fix, push it to the PR and watch both workflows to green:

```bash
gh run list --branch <branch> -L 4
gh run watch <id> --exit-status --interval 60
```

## Done

Report the following:
- the release URL;
- the tag and SHA;
- the Reproducibility run;
- the fdroiddata fork commit;
- the MR (number and link, or the ready-to-open link and text);
- the mirror PR;
- any drafted comments the user still has to post.

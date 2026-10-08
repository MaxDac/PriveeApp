---
name: cross-repo-change
description: Make a change that spans the Privee server (MaxDac/Privee) and the Android app (MaxDac/PriveeApp, com.privee.app). Covers the shared contract (REST under /api/app, Phoenix channels, api_version, push payload, libsignal version), the safe order of work (server first and backward compatible, deploy, then app release, then F-Droid), app-side implementation in core/net, core/signal and app, tests, and compatibility with older app versions still installed from F-Droid. Use when a feature, fix or refactor needs both repos, when the server API or channel events change, when bumping api_version, or when asked how the app and server fit together.
---

# Change both repos

Privee is two repositories:
- **MaxDac/Privee**: the Phoenix server and the website, with a libsignal WASM client.
- **MaxDac/PriveeApp**: this Android app, with a libsignal Android client.

Deployment is owned separately by [MaxDac/PriveeDeploy](https://github.com/MaxDac/PriveeDeploy).
Privee runs CI only; merging a server PR does not deploy it.

The canonical contract and change order live on the server side:
- <https://github.com/MaxDac/Privee/blob/main/docs/cross-repo.md>: the contract, version rules and release order;
- <https://github.com/MaxDac/Privee/blob/main/docs/client-api.md>: endpoints and channel events;
- <https://github.com/MaxDac/Privee/blob/main/docs/e2e-encryption.md>: the protocol.

On the server, the matching skill is `api-change`.

This file exists twice and the two copies must stay byte-identical:
- `.claude/skills/cross-repo-change/SKILL.md`;
- `.github/skills/cross-repo-change/SKILL.md`.

`scripts/tests/test_skill_copies.py` enforces this.

## Why order matters

Server deployment is manual and can reach users before an app update. App updates reach users days or weeks later, and F-Droid builds lag further, so old app versions stay in use for a long time. Therefore:

1. **Server first, backward compatible.**
   - Add new endpoints, events and fields.
   - Keep old ones working.
   - New request fields must be optional.
   - Never change the meaning of an existing field.
2. **Deploy the server separately.** Follow the server's
   [`deploy-privee` skill](https://github.com/MaxDac/Privee/blob/main/.github/skills/deploy-privee/SKILL.md).
   Check successful server CI for the full source commit SHA, obtain explicit
   production-deploy confirmation, then trigger PriveeDeploy's manual workflow:

   ```bash
   gh workflow run deploy.yml -R MaxDac/PriveeDeploy -f ref=<full-source-sha>
   ```

   For forks, use the owner's deploy repository and verify its `PRIVEE_REPO`
   setting matches the server repository. Wait for deployment to succeed and
   verify `GET <server>/api/app/info` before releasing an app that needs it.
3. **App change.** Use the new contract, and degrade gracefully on servers that do not have it yet: self-hosted instances update on their own schedule.
4. **App release** (`fdroid-release` skill), then F-Droid picks it up.
5. **Remove the old server behaviour** only after no supported app version uses it.

A breaking change needs an explicit migration plan, not just a new
`api_version`. The discovery response reports one integer and released apps
currently accept only `1`: changing it immediately locks those apps out.
First ship an app that accepts both versions and works with the old server.
Keep existing server behaviour working while users update, including F-Droid
users. Only then deploy the API-version change, with an agreed compatibility
window. Additive, backward-compatible changes keep API version `1`.

## App-side checklist

1. **Read the server PR and docs first.** Work in a separate clone of the server if you need to read code (`gh repo clone MaxDac/Privee`).
2. **REST:**
   - edit `core/net/src/main/kotlin/com/privee/net/PriveeApi.kt`;
   - add a MockWebServer test in `PriveeApiTest`;
   - decode with `ignoreUnknownKeys`-style tolerance, so the server can add fields.
3. **Channels:**
   - incoming events go in `PriveeSession.handle` (topic `session`) or in `ChatConversation` (topic `chat:<peer>`);
   - calls go through `ServerCall` in `SignalClient`;
   - unknown events must be ignored, not treated as errors.
4. **Protocol or key changes** go in `core/signal` and need tests in `SignalClientTest`. Both clients must agree, so check the website's behaviour and libsignal version (`libsignal-upgrade` skill).
5. **`api_version`:** update `ServerInfo.SUPPORTED_API_VERSIONS`, `ServerInfoTest`, and the "Choosing a server" section of the README.
6. **Push:** pushes are wake-ups only. Never add message content, sender names or keys to a push payload, on either side.
7. **Test against a local server.** Run the server's `main` (or the PR branch) and the debug app on an emulator at `http://10.0.2.2:4000`; see the `store-screenshots` skill, step 1.
8. **PR.**
   - Use Conventional Commits, for example `feat(chat): …`, and link the server PR.
   - The four required checks must pass.
   - Do not touch `version.properties`.

## Invariants (both repos)

- The server only stores public key material, ciphertext and metadata it needs for routing. It never stores plaintext or private keys.
- Push payloads contain no content.
- Every API change stays compatible with released app versions; an API-version bump requires the staged migration above.
- Both repos are AGPL-3.0-only. The server exposes its source URL (`PRIVEE_SOURCE_URL`), and forks must keep doing so.

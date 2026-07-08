# Publishing Scapestack Sync to the RuneLite Plugin Hub

The Hub only accepts plugins that live in their own GitHub repo (one
plugin per repo). This monorepo holds the canonical source; we extract
a publish-ready copy to a sibling repo with `extract-plugin.sh`.

## One-time: create the GitHub repo

1. On github.com, create `laurensbs/scapestack-runelite-plugin` (empty,
   no README, no license — the extract script seeds those).
2. Note the SSH URL; you'll pass it to the extract script.

## Each release

From the monorepo root:

```sh
npm run ci:check
npm run plugin:release-plan
./scripts/extract-plugin.sh ~/code/scapestack-runelite-plugin
cd ~/code/scapestack-runelite-plugin
git add -A
git commit -m "Release v0.2.0"
git tag v0.2.0
git push --tags origin main
```

After pushing the standalone repo, copy the new full commit SHA and update
`runelite/plugin-hub` file `plugins/scapestack-sync`:

```text
repository=https://github.com/laurensbs/scapestack-runelite-plugin.git
commit=<new full sha from the standalone repo>
```

This is the critical handoff: RuneLite reviewers only inspect the commit pinned
in Plugin Hub. Local monorepo improvements do not affect review until they are
extracted, committed, pushed, and the Plugin Hub PR pin is updated.

Before asking maintainers to re-review, run:

```sh
npm run plugin:review-packet
npm run plugin:review-reply-command
npm run plugin:review-handoff-command
```

Paste that packet into the Plugin Hub PR body or run the reply command from an
authenticated GitHub CLI checkout to add it as a maintainer reply. The handoff
command prints the full GitHub CLI sequence that rewrites the stale PR body and
adds the reviewer packet comment in one pass. It keeps the review text aligned
with the actual plugin: sync defaults are off, posted data is limited to opt-in
game-state, HTTP runs off the RuneLite client thread, and the raw token is only sent as the Authorization bearer for claim and sync requests. Replace stale PR-body copy
if it still says sync-on-login defaults on or implies the raw token never leaves the client.

Also keep the web-app merge contract visible in the PR body: successful sync
opens `/next?rsn=...&source=plugin-sync&bank=none`, where `source=plugin-sync`
loads the verified account-progress payload and `bank=none` prevents stale
browser bank context from being silently reused. Gear-aware advice still comes
from a separate browser-only Bank Memory or Bank Tags paste.

The extract script:
- Mirrors `plugin/` to the target directory (preserves Git history
  for files that share names — use `--clean` to start fresh).
- Copies `LICENSE` from the monorepo if present.
- Generates a top-level README pointing back here.
- Does NOT delete the original `plugin/` tree — the monorepo stays
  authoritative for ongoing development.

## Hub submission

Once the standalone repo has a tagged release:

1. Fork [runelite/plugin-hub](https://github.com/runelite/plugin-hub).
2. Add a new file `plugins/scapestack-sync` containing:
   ```
   repository=https://github.com/laurensbs/scapestack-runelite-plugin.git
   commit=<full sha of the tagged release>
   ```
3. Open a PR. CI runs `./gradlew build` against the pinned commit; if
   it goes green, a RuneLite maintainer reviews the code.
4. Review can take 2–6 weeks. Common reasons for rejection:
   - **External HTTP calls without user opt-in.** `Sync on login`
     and `Refresh after quests` both default off. The player must enable
     them in RuneLite settings before any progress POST happens. Document
     this in the PR body.
   - **PII/data leakage.** We POST RSN, plugin version, quest/diary state,
     loaded collection-log item IDs, Slayer state, and the local claim token.
     We do not collect a RuneScape password, bank/inventory/equipment, chat,
     inputs, screenshots, local files, IP, or machine fingerprint. Call this
     out in the PR body.
   - **Ambiguous web handoff.** State that plugin sync opens a bankless
     `/next` URL with `source=plugin-sync&bank=none`; bank context is a
     separate browser-only paste and never flows through RuneLite.
   - **Long-running work on the client thread.** Our sync runs on a named
     daemon background thread, and plugin shutdown cancels the active OkHttp
     call and suppresses further chat feedback while that worker returns
     normally. Verify before submitting.
5. Once merged, the plugin appears in the in-game Plugin Hub within
   ~30 min (CI rebuild cadence).

## Verifying before submission

- `npm run ci:check` green from the monorepo root
- `npm run plugin:release-plan` reviewed
- `./gradlew build` clean
- `./gradlew test` all green
- Plugin loads in `./gradlew runClient` without errors in the log
- Triggering a sync against a local backend returns 200 (claim + sync
  both succeed; no `403 Token does not match` after the first run)
- Switching characters on the same install re-claims cleanly (the
  plugin clears `claimedRsn` and re-POSTs `/api/sync/claim`)

## Versioning

Plugin version must stay identical across:

- `package.json` (`version`) — web app release version
- `src/lib/plugin-sync.ts` (`CURRENT_PLUGIN_VERSION`) — web checker expectation
- `plugin/build.gradle` (`version = '...'`) — RuneLite build metadata
- `plugin/runelite-plugin.properties` (`version=...`) — Plugin Hub manifest
- `ScapestackSyncPlugin.PLUGIN_VERSION` — payload/chat/user-agent version

Run `npm test -- tests/plugin-version-drift.test.ts` before every release.
That test intentionally fails if any of those values drift.

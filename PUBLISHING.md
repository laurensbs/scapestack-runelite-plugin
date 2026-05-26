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
./scripts/extract-plugin.sh ~/code/scapestack-runelite-plugin
cd ~/code/scapestack-runelite-plugin
git add -A
git commit -m "Release v0.2.0"
git tag v0.2.0
git push --tags origin main
```

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
   - **External HTTP calls without user opt-in.** Ours are opt-in via
     the `autoSync` config (defaults on, but visible in settings).
     Document this in the PR body.
   - **PII leakage.** We POST RSN + plugin version. No IP, no machine
     fingerprint. Call this out in the PR body.
   - **Long-running work on the client thread.** Our sync runs on
     `new Thread(...)` — verify before submitting.
5. Once merged, the plugin appears in the in-game Plugin Hub within
   ~30 min (CI rebuild cadence).

## Verifying before submission

- `./gradlew build` clean
- `./gradlew test` all green
- Plugin loads in `./gradlew runClient` without errors in the log
- Triggering a sync against a local backend returns 200 (claim + sync
  both succeed; no `403 Token does not match` after the first run)
- Switching characters on the same install re-claims cleanly (the
  plugin clears `claimedRsn` and re-POSTs `/api/sync/claim`)

## Versioning

Plugin version lives in `build.gradle` (`version = '...'`) and as a
constant in `ScapestackSyncPlugin.PLUGIN_VERSION`. Bump both on every
release; CI on the standalone repo will read the gradle version, and
the server logs the constant for observability.

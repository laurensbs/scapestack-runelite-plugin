# Publishing Scapestack Sync to the RuneLite Plugin Hub

The Plugin Hub builds one immutable commit from the standalone repository. The
monorepo is the source tree, but `plugin/release-manifest.json` is the release
authority.

That manifest deliberately separates:

- `candidate`: what the monorepo and website are being prepared to support;
- `published`: the version and full source commit currently pinned by Plugin
  Hub master;
- `reviewPullRequest`: historical review context only. An old PR is never used
  to decide what RuneLite currently installs.

Do not publish candidate `0.3.0` until REC-02 completes and verifies the full
snapshot contract. REC-01 only makes the handoff truthful and reproducible.

## Reproducible RuneLite resolution

Current official Plugin Hub guidance expects `latest.release` for RuneLite
client compatibility. `plugin/build.gradle` therefore keeps that selector, but
all resolved dependencies are committed in `plugin/gradle.lockfile`. The
manifest records the RuneLite release against which that lock was verified.

When RuneLite publishes a new release:

```sh
cd plugin
./gradlew dependencies --write-locks
./gradlew clean test
cd ..
```

Review the lock diff and update `candidate.verifiedRuneLiteRelease` in
`plugin/release-manifest.json` in the same commit. Never edit the lock by hand.

## Before extracting a candidate

Run from the monorepo root:

```sh
npm run ci:check
npm run plugin:release-plan
./scripts/extract-plugin.sh ~/code/scapestack-runelite-plugin --dry-run
```

The offline release check proves candidate version parity, contract ownership,
opt-in defaults, dependency locking and the standalone file surface. The plan
also lists local release-impact files so `.repo-git` worktrees cannot silently
look clean.

Plugin version ownership is intentionally not tied to the web app's
`package.json` version. Candidate plugin state comes from:

- `plugin/release-manifest.json` - semantic version, contract versions and
  verified RuneLite release;
- `src/lib/plugin-sync.ts` - exposes the candidate for release checks and the
  published version for player-facing update checks;
- `plugin/build.gradle` - imports the candidate version and RuneLite selector;
- `plugin/runelite-plugin.properties` - candidate Plugin Hub metadata;
- `ScapestackSyncPlugin.PLUGIN_VERSION` - candidate payload and user-agent
  version;
- `plugin/gradle.lockfile` - exact resolved build dependencies.

`tests/plugin-version-drift.test.ts` and `npm run plugin:release-check` fail when
those sources drift.

## Extract, test and push the standalone candidate

Only continue after the candidate contract is release-ready:

```sh
./scripts/extract-plugin.sh ~/code/scapestack-runelite-plugin
cd ~/code/scapestack-runelite-plugin
./gradlew clean test
git status --short
git add -A
git commit -m "Release v0.3.0"
git push origin main
git rev-parse HEAD
```

Do not tag or submit a commit that has not passed the standalone Gradle test.
Copy the full 40-character SHA printed by `git rev-parse HEAD`.

The extract script mirrors `plugin/`, copies the license and generates the
standalone README. It never deletes the monorepo source. Use `--clean` only when
you intentionally want a fresh target tree.

## Update Plugin Hub

In a fork of `runelite/plugin-hub`, update
`plugins/scapestack-sync` to the tested standalone commit:

```text
repository=https://github.com/laurensbs/scapestack-runelite-plugin.git
commit=<full tested standalone SHA>
```

Open a new update PR. The PR body must describe the candidate that is actually
pinned. It must state:

- **External HTTP calls without user opt-in:** `Sync on login` and `Refresh
  after quests` default off. Manual sync is an explicit player action.
- **Bank privacy:** when bank sync is enabled, Scapestack sends bank item names,
  IDs and quantities for gear and supply planning. It never sends inventory,
  equipment, chat, screenshots, clicks, local files, login details or a
  RuneScape password.
- **Transport:** claim and sync use the raw install token only as the
  Authorization bearer. The server stores its hash.
- **Threading:** collection and HTTP work do not block RuneLite's client thread.
- **Web handoff:** successful sync opens
  `/next?rsn=...&source=plugin-sync&bank=none`; the website loads the persisted
  plugin snapshot instead of reusing stale browser bank context.

Local monorepo improvements do not affect Plugin Hub review until they are
extracted, committed, pushed and pinned in that file.

## Record publication truth

After Plugin Hub master contains the new commit:

1. Update `published.version`, `published.contractVersion` and
   `published.sourceCommit` in `plugin/release-manifest.json`.
2. Confirm the candidate values still describe the next supported artifact.
3. Run:

```sh
npm run plugin:release-check
npm run plugin:release-check:live
npm run plugin:release-evidence
```

`plugin:release-check:live` reads the actual Plugin Hub master entry, the pinned
standalone artifact, standalone `main` and official RuneLite Maven metadata. A
historical PR being closed, merged, stale or unavailable is informational and
cannot create a false release failure.

`plugin:release-evidence` prints JSON containing the monorepo commit, candidate
contract, declared published artifact, observed Plugin Hub pin, standalone head
and dependency release. Save it with the release handoff.

The live gate fails when:

- Plugin Hub master points at another repository or commit;
- the pinned artifact version differs from the declared published version;
- standalone `main` contains an untracked version;
- the official RuneLite release no longer matches the committed lock.

An intentional candidate ahead of the published artifact is reported separately
and is not confused with production drift.

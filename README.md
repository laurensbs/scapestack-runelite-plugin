# Scapestack Sync (RuneLite plugin)

Syncs your OSRS quest, diary, collection-log, and Slayer state to
[scapestack.org](https://www.scapestack.org) after you opt in via
`Auto-sync on login`, so Path-to-Max can label quest, diary, collection-log
and Slayer coverage from a verified RuneLite payload instead of only
hiscores heuristics.

The plugin does not POST progress by default. Enable `Auto-sync on login`
in RuneLite settings to send login snapshots; optionally enable
`Sync on quest complete` for immediate quest refreshes.

When sync succeeds, RuneLite chat shows the verified `/next` link for that RSN
(`?source=plugin-sync&bank=none`). Local/self-hosted Sync endpoints produce
local/self-hosted `/next` links, so testers do not accidentally jump to
production after syncing against `localhost`.

## Data contract

Sent after opt-in: RSN, plugin version, quest and diary completion,
loaded collection-log item IDs, Slayer state, and the local install token
only as the Authorization bearer on claim/sync requests.

Never sent: RuneScape password, bank, inventory, equipment, GE offers, chat,
friends list, clicks, key presses, screenshots, local files, or RuneLite
config folders, IP address, or machine fingerprint.

The server stores `sha256(token) → RSN` first-wins. The raw token stays
local except for HTTPS claim and sync requests where it is sent as
`Authorization: Bearer <token>`.

This repo is the publish-ready mirror of the canonical source in
[laurensbs/scapestack/plugin](https://github.com/laurensbs/scapestack/tree/main/plugin).
Bug reports, PRs, and roadmap discussion happen in the main repo.

## Install via Plugin Hub

In RuneLite: Configuration → Plugin Hub → search "Scapestack Sync."

## Build locally

```sh
./gradlew build
./gradlew test
./gradlew runClient
```

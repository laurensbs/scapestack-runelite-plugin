# Scapestack Sync (RuneLite plugin)

Syncs your OSRS account mode, skills and XP, quests, diaries,
observed boss kill counts, collection log, Slayer state and optional bank context to
[scapestack.org](https://www.scapestack.org). Use `Sync now` for a manual
refresh or opt in to `Sync on login` for automatic snapshots, so the session
planner can use RuneLite state instead of guessing from Hiscores alone.

The plugin does not POST progress by default. Enable `Sync on login`
in RuneLite settings to send login snapshots. Bank readiness is included by default
with item IDs/names/quantities when your bank has been opened; turn off
`Use bank for readiness` if you only want progress sync. Optionally enable
`Refresh after quests` for immediate quest refreshes.
Use `Sync now` when you want to refresh the planner on demand; the toggle
resets automatically after the sync starts.

When sync succeeds, RuneLite chat stays compact: it confirms that Scapestack
was updated and tells the player to open `/next`. It does not print the sync
URL or a long query string. Local/self-hosted endpoints, including localhost, still keep the verified
`/next?rsn=...&source=plugin-sync&bank=none` web state available for testers.

For collection-log accuracy, open the in-game Collection Log once and click the
relevant tabs/categories before syncing. RuneLite only exposes collection-log
item widgets after the game has loaded them, so the plugin now tells you whether
the log was not opened, opened without item slots, or loaded correctly.

## Data contract

Sent after a manual sync or automatic-sync opt-in: RSN, account type, plugin
and contract version, skill levels and XP, quest and diary completion, observed
boss kill counts, loaded collection-log item IDs, Slayer state,
bank item IDs/names/quantities when bank checks are on, and the local install token
only as the Authorization bearer on claim/sync requests.

Never sent: RuneScape password, inventory, equipment, GE offers, chat,
friends list, clicks, key presses, screenshots, local files, or RuneLite
config folders or a machine fingerprint. The JSON payload does not contain an
IP address; as with any HTTPS request, the destination server receives the
connection IP as transport metadata.

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

# Scapestack Sync (RuneLite plugin)

Syncs your OSRS quest, diary, and collection-log state to
[scapestack.org](https://www.scapestack.org) so its Path-to-Max
recommender works from real data instead of hiscores heuristics.

This repo is the publish-ready mirror of the canonical source in
[laurensbs/scapestack/plugin](https://github.com/laurensbs/scapestack/tree/main/plugin).
Bug reports, PRs, and roadmap discussion happen in the main repo.

## What it captures

| Signal | Source |
| --- | --- |
| Quests completed | `Quest.getState(client)` per quest enum |
| Diary tier completion | VarPlayer/Varbit table (48 entries, sourced from QuestHelper) |
| Collection log items | Widget tree walk (group 621), accumulated across session |

The collection-log accumulator fills as the player browses tabs in the
in-game Collection Log; the player has to actually open it at least once
per session. Diary state and quest state are read instantly on login.

## Auth

Each install generates a UUID on first run, stored via `ConfigManager`
under `scapestackSync.installToken`. The first sync POSTs that token
to `https://www.scapestack.org/api/sync/claim`, which binds
`sha256(token) → RSN` first-wins. Subsequent syncs carry
`Authorization: Bearer <token>`. The server rejects syncs whose hash
doesn't match the bound claim — so a malicious peer can't overwrite
your row by guessing your RSN.

## Privacy

The plugin POSTs your RSN, plugin version, and game-state snapshots
(quest list, diary completion, collection-log item IDs) to
`https://www.scapestack.org/api/sync`. No IP address, no machine
fingerprint, no chat-log content. The install token never travels in
plain text beyond the initial HTTPS claim handshake.

## Install via Plugin Hub

In RuneLite: Configuration → Plugin Hub → search "Scapestack Sync."

## Build locally

Requires JDK 11.

```sh
./gradlew build      # compiles + jars
./gradlew test       # JUnit suite — 15 unit tests always run;
                     #               9 E2E need a live dev-server
./gradlew runClient  # launches RuneLite with the plugin side-loaded
```

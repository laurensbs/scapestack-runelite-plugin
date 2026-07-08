package app.scapestack.runelite;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("scapestackSync")
public interface ScapestackSyncConfig extends Config {

    @ConfigItem(
        keyName = "autoSync",
        name = "Sync on login",
        description = "Updates your Scapestack session planner with account type, quests, skills, diaries, collection-log IDs and Slayer state. Bank readiness stays separate."
    )
    default boolean autoSync() {
        return false;
    }

    @ConfigItem(
        keyName = "syncBankItems",
        name = "Use bank for readiness",
        description = "Adds bank item IDs, names and quantities so quests and diaries can show ready/missing item checks. Never sends inventory, equipment, chat, screenshots or account login."
    )
    default boolean syncBankItems() {
        return false;
    }

    @ConfigItem(
        keyName = "syncOnQuestComplete",
        name = "Refresh after quests",
        description = "Updates Scapestack right after a quest completion. Requires Sync on login to be enabled."
    )
    default boolean syncOnQuestComplete() {
        return false;
    }

    @ConfigItem(
        keyName = "forceClaimOnNextSync",
        name = "Reconnect player",
        description = "Forget the local claimed-RSN cache and reconnect this RuneLite install on the next sync. Use after changing RSN or fixing a rejected claim."
    )
    default boolean forceClaimOnNextSync() {
        return false;
    }

    @ConfigItem(
        keyName = "chatFeedback",
        name = "Compact chat updates",
        description = "Show short RuneLite chat updates when Scapestack starts, completes or needs attention."
    )
    default boolean chatFeedback() {
        return true;
    }
}

package app.scapestack.runelite;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("scapestackSync")
public interface ScapestackSyncConfig extends Config {

    @ConfigItem(
        keyName = "syncNow",
        name = "Sync now",
        description = "Refresh your ScapeStack planner now."
    )
    default boolean syncNow() {
        return false;
    }

    @ConfigItem(
        keyName = "autoSync",
        name = "Sync on login",
        description = "Keeps your planner current with account mode, skills, quests, diaries, Slayer task and bank readiness."
    )
    default boolean autoSync() {
        return false;
    }

    @ConfigItem(
        keyName = "syncBankItems",
        name = "Use bank for readiness",
        description = "Includes bank item names, IDs and quantities for trip readiness. Turn off if you only want progress sync. Never sends inventory, equipment, chat, screenshots or login details."
    )
    default boolean syncBankItems() {
        return true;
    }

    @ConfigItem(
        keyName = "syncOnQuestComplete",
        name = "Refresh after quests",
        description = "Refreshes ScapeStack after a quest completion. Requires Sync on login."
    )
    default boolean syncOnQuestComplete() {
        return false;
    }

    @ConfigItem(
        keyName = "forceClaimOnNextSync",
        name = "Reconnect player",
        description = "Reconnect this RuneLite install to your current player. Use this after changing RSN."
    )
    default boolean forceClaimOnNextSync() {
        return false;
    }

    @ConfigItem(
        keyName = "chatFeedback",
        name = "Compact chat updates",
        description = "Show short RuneLite chat updates when ScapeStack checks your progress or needs attention."
    )
    default boolean chatFeedback() {
        return true;
    }
}

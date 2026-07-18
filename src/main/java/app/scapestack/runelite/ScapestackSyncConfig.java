package app.scapestack.runelite;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

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
        description = "Keeps your planner current with account mode, skills, XP, quests, diaries, boss KC RuneLite has seen, Slayer task and bank items."
    )
    default boolean autoSync() {
        return false;
    }

    @Range(
        min = 5,
        max = 60
    )
    @ConfigItem(
        keyName = "autoSyncIntervalMinutes",
        name = "Refresh every",
        description = "When Sync on login is on, refresh ScapeStack quietly while you play."
    )
    default int autoSyncIntervalMinutes() {
        return 15;
    }

    @ConfigItem(
        keyName = "syncBankItems",
        name = "Use bank for trips",
        description = "Includes bank item names, IDs and quantities for gear and supplies. Turn off if you only want progress sync. Never sends inventory, equipment, chat, screenshots or login details."
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

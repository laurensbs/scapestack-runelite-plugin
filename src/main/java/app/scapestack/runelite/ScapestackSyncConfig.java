package app.scapestack.runelite;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("scapestackSync")
public interface ScapestackSyncConfig extends Config {

    @ConfigItem(
        keyName = "syncUrl",
        name = "Sync endpoint",
        description = "Where the plugin POSTs your progress. Leave at the default unless you're self-hosting Scapestack."
    )
    default String syncUrl() {
        return "https://www.scapestack.org/api/sync";
    }

    @ConfigItem(
        keyName = "autoSync",
        name = "Auto-sync on login",
        description = "Opt in to sending quests, diaries, collection-log IDs and Slayer state. Never sends bank, inventory, equipment, chat, screenshots or account login."
    )
    default boolean autoSync() {
        return false;
    }

    @ConfigItem(
        keyName = "syncOnQuestComplete",
        name = "Sync on quest complete",
        description = "Opt in to an extra POST after quest completion so Scapestack refreshes within seconds. Requires Auto-sync on login to be enabled."
    )
    default boolean syncOnQuestComplete() {
        return false;
    }

    @ConfigItem(
        keyName = "forceClaimOnNextSync",
        name = "Force claim retry",
        description = "Forget the local claimed-RSN cache and re-run the claim step on the next sync. Use after changing RSN or fixing a rejected claim."
    )
    default boolean forceClaimOnNextSync() {
        return false;
    }

    @ConfigItem(
        keyName = "chatFeedback",
        name = "Show chat feedback",
        description = "Show a small RuneLite chat message when Scapestack starts, completes, or fails a sync."
    )
    default boolean chatFeedback() {
        return true;
    }
}

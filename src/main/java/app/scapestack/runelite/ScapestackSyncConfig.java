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
        description = "When enabled, the plugin captures your quest/diary/CL state on every login and POSTs it."
    )
    default boolean autoSync() {
        return true;
    }

    @ConfigItem(
        keyName = "syncOnQuestComplete",
        name = "Sync on quest complete",
        description = "Trigger an extra sync each time you finish a quest — keeps Scapestack's data fresh within seconds."
    )
    default boolean syncOnQuestComplete() {
        return true;
    }
}

package app.scapestack.runelite;

import net.runelite.client.config.ConfigItem;
import org.junit.Test;

import static org.junit.Assert.*;

public class ScapestackSyncConfigTest {
    private final ScapestackSyncConfig config = new ScapestackSyncConfig() {};

    @Test
    public void defaultsRequireExplicitSyncOptIn() {
        assertEquals("https://www.scapestack.org/api/sync", config.syncUrl());
        assertFalse(config.autoSync());
        assertFalse(config.syncOnQuestComplete());
        assertFalse(config.forceClaimOnNextSync());
        assertTrue(config.chatFeedback());
    }

    @Test
    public void configCopyStatesOptInDataBoundary() throws Exception {
        String autoSyncDescription = ScapestackSyncConfig.class
            .getMethod("autoSync")
            .getAnnotation(ConfigItem.class)
            .description();
        String questSyncDescription = ScapestackSyncConfig.class
            .getMethod("syncOnQuestComplete")
            .getAnnotation(ConfigItem.class)
            .description();

        assertTrue(autoSyncDescription.contains("quests, diaries, collection-log IDs and Slayer state"));
        assertTrue(autoSyncDescription.contains("Never sends bank, inventory, equipment, chat, screenshots or account login"));
        assertTrue(questSyncDescription.contains("Requires Auto-sync on login"));
    }
}

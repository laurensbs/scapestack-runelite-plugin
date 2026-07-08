package app.scapestack.runelite;

import net.runelite.client.config.ConfigItem;
import org.junit.Test;

import static org.junit.Assert.*;

public class ScapestackSyncConfigTest {
    private final ScapestackSyncConfig config = new ScapestackSyncConfig() {};

    @Test
    public void defaultsRequireExplicitSyncOptIn() {
        assertFalse(config.autoSync());
        assertFalse(config.syncBankItems());
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
        String bankSyncDescription = ScapestackSyncConfig.class
            .getMethod("syncBankItems")
            .getAnnotation(ConfigItem.class)
            .description();

        assertTrue(autoSyncDescription.contains("account type, quests, skills, diaries, collection-log IDs and Slayer state"));
        assertTrue(autoSyncDescription.contains("Bank readiness stays separate"));
        assertTrue(bankSyncDescription.contains("bank item IDs, names and quantities"));
        assertTrue(bankSyncDescription.contains("Never sends inventory, equipment, chat, screenshots or account login"));
        assertTrue(questSyncDescription.contains("Requires Sync on login"));
    }

    @Test
    public void syncEndpointIsNotUserVisibleConfig() throws Exception {
        for (java.lang.reflect.Method method : ScapestackSyncConfig.class.getMethods()) {
            ConfigItem item = method.getAnnotation(ConfigItem.class);
            if (item == null) continue;
            assertNotEquals("syncUrl", item.keyName());
            assertFalse(item.name().toLowerCase().contains("endpoint"));
            assertFalse(item.description().toLowerCase().contains("self-hosting"));
        }
    }
}

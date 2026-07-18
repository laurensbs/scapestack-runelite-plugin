package app.scapestack.runelite;

import net.runelite.client.config.ConfigItem;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class ScapestackSyncConfigTest {
    private final ScapestackSyncConfig config = new ScapestackSyncConfig() {};

    @Test
    public void defaultsRequireExplicitSyncOptIn() {
        assertFalse(config.syncNow());
        assertFalse(config.autoSync());
        assertEquals(15, config.autoSyncIntervalMinutes());
        assertTrue(config.syncBankItems());
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
        String intervalDescription = ScapestackSyncConfig.class
            .getMethod("autoSyncIntervalMinutes")
            .getAnnotation(ConfigItem.class)
            .description();
        String bankSyncDescription = ScapestackSyncConfig.class
            .getMethod("syncBankItems")
            .getAnnotation(ConfigItem.class)
            .description();
        String syncNowDescription = ScapestackSyncConfig.class
            .getMethod("syncNow")
            .getAnnotation(ConfigItem.class)
            .description();

        assertTrue(syncNowDescription.contains("Refresh your ScapeStack planner now"));
        assertTrue(autoSyncDescription.contains("account mode, skills, XP, quests, diaries, boss KC RuneLite has seen, Slayer task and bank items"));
        assertTrue(intervalDescription.contains("refresh ScapeStack quietly while you play"));
        assertTrue(bankSyncDescription.contains("Includes bank item names, IDs and quantities"));
        assertTrue(bankSyncDescription.contains("Turn off if you only want progress sync"));
        assertTrue(bankSyncDescription.contains("Never sends inventory, equipment, chat, screenshots or login details"));
        assertTrue(questSyncDescription.contains("Requires Sync on login"));

        assertNoNormalUserTech(syncNowDescription);
        assertNoNormalUserTech(autoSyncDescription);
        assertNoNormalUserTech(intervalDescription);
        assertNoNormalUserTech(bankSyncDescription);
        assertNoNormalUserTech(questSyncDescription);
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

    @Test
    public void hiddenDevOverrideUsesJvmPropertyOnly() throws Exception {
        String source = Files.readString(
            Path.of("src/main/java/app/scapestack/runelite/ScapestackSyncPlugin.java"),
            StandardCharsets.UTF_8
        );

        assertTrue(source.contains("System.getProperty(DEV_SYNC_URL_PROPERTY)"));
        assertFalse(source.contains("System.getenv"));
        assertFalse(source.contains("SCAPESTACK_SYNC_URL"));
    }

    private static void assertNoNormalUserTech(String copy) {
        String lower = copy.toLowerCase();
        assertFalse(lower.contains("url"));
        assertFalse(lower.contains("endpoint"));
        assertFalse(lower.contains("payload"));
        assertFalse(lower.contains("http"));
    }
}

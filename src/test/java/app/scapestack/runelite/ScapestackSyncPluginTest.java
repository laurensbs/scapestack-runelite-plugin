package app.scapestack.runelite;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;
import okio.Timeout;
import org.junit.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.GameState;
import net.runelite.client.events.ConfigChanged;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScapestackSyncPluginTest {

    @Test
    public void syncPayloadMatchesDocumentedDataContract() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.questsCompleted = Arrays.asList("Cook's Assistant", "Dragon Slayer I");
        snapshot.skills = Arrays.asList(
            new GameStateReader.SkillLevel("Agility", 35, 22406),
            new GameStateReader.SkillLevel("Ranged", 70, 737627)
        );
        snapshot.diariesCompleted = Collections.singletonList(
            new GameStateReader.DiaryCompletion("Karamja", "Easy")
        );
        snapshot.collectionLogItemIds = Arrays.asList(11785, 11787, 12922);
        snapshot.collectionLogStatus = new CollectionLogReader.Status(true, 1, 100, 3);
        snapshot.bankItems = Arrays.asList(
            new GameStateReader.BankItem(1511, "Logs", 6),
            new GameStateReader.BankItem(2351, "Iron bar", 5)
        );
        snapshot.slayer = new GameStateReader.SlayerState(
            132,
            51,
            47,
            19,
            "Dust devils",
            "Catacombs of Kourend",
            Arrays.asList(1, 2),
            Arrays.asList("Banshees", "Black demons")
        );
        snapshot.bossKc = Collections.singletonMap("Vorkath", 48);

        JsonObject payload = ScapestackSyncPlugin.buildSyncPayload("Lynx Titan", snapshot, new Gson());
        Set<String> fields = payload.keySet();

        assertEquals(new HashSet<>(Arrays.asList(
            "rsn",
            "displayName",
            "pluginVersion",
            "contractVersion",
            "capturedAt",
            "coverage",
            "accountType",
            "skills",
            "questsCompleted",
            "diariesCompleted",
            "collectionLogItemIds",
            "collectionLogStatus",
            "bossKc",
            "bankStatus",
            "bankItems",
            "slayer"
        )), fields);

        assertFalse(fields.contains("password"));
        assertFalse(fields.contains("email"));
        assertFalse(fields.contains("inventory"));
        assertFalse(fields.contains("equipment"));
        assertFalse(fields.contains("chat"));
        assertFalse(fields.contains("inputs"));
        assertFalse(fields.contains("screenshots"));
        assertFalse(fields.contains("files"));

        JsonObject slayer = payload.getAsJsonObject("slayer");
        assertEquals(new HashSet<>(Arrays.asList(
            "points",
            "streak",
            "taskRemaining",
            "currentTaskId",
            "taskName",
            "taskLocation",
            "blocks",
            "blockNames"
        )), slayer.keySet());
        assertEquals("Dust devils", slayer.get("taskName").getAsString());
        assertEquals("Catacombs of Kourend", slayer.get("taskLocation").getAsString());
        assertEquals("Banshees", slayer.getAsJsonArray("blockNames").get(0).getAsString());
        assertEquals("normal", payload.get("accountType").getAsString());
        assertEquals(2, payload.getAsJsonArray("skills").size());
        assertEquals("Agility", payload.getAsJsonArray("skills").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(35, payload.getAsJsonArray("skills").get(0).getAsJsonObject().get("level").getAsInt());
        assertEquals(22406, payload.getAsJsonArray("skills").get(0).getAsJsonObject().get("xp").getAsInt());
        assertEquals(2, payload.getAsJsonArray("bankItems").size());
        assertEquals(1511, payload.getAsJsonArray("bankItems").get(0).getAsJsonObject().get("id").getAsInt());
        assertEquals(6, payload.getAsJsonArray("bankItems").get(0).getAsJsonObject().get("quantity").getAsInt());
        JsonObject collectionLogStatus = payload.getAsJsonObject("collectionLogStatus");
        assertTrue(collectionLogStatus.get("opened").getAsBoolean());
        assertEquals(1, collectionLogStatus.get("widgetLoads").getAsInt());
        assertEquals(100, collectionLogStatus.get("lastWidgetItemCount").getAsInt());
        assertEquals(3, collectionLogStatus.get("obtainedItemCount").getAsInt());
        assertTrue(payload.getAsJsonObject("bankStatus").get("enabled").getAsBoolean());
        assertEquals(2, payload.getAsJsonObject("bankStatus").get("itemCount").getAsInt());
        assertEquals(3, payload.get("contractVersion").getAsInt());
        assertEquals(48, payload.getAsJsonObject("bossKc").get("Vorkath").getAsInt());
        assertEquals("available", payload.getAsJsonObject("coverage").getAsJsonObject("bossKc").get("state").getAsString());
        assertEquals("available", payload.getAsJsonObject("coverage").getAsJsonObject("skills").get("state").getAsString());
    }

    @Test
    public void committedV3FixtureMatchesProductionSerializer() throws Exception {
        Path fixture = Paths.get("..", "tests", "fixtures", "plugin-sync-v3.json");
        if (!Files.isRegularFile(fixture)) {
            fixture = Paths.get("src", "test", "resources", "fixtures", "plugin-sync-v3.json");
        }

        try (Reader reader = Files.newBufferedReader(
            fixture,
            java.nio.charset.StandardCharsets.UTF_8
        )) {
            JsonObject committed = new Gson().fromJson(reader, JsonObject.class);
            assertEquals(SyncPayloadFixtureWriter.fixturePayload(), committed);
        }
    }

    @Test
    public void syncPayloadCarriesAccountType() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.accountType = "ultimate_ironman";

        JsonObject payload = ScapestackSyncPlugin.buildSyncPayload("Uim Lynx", snapshot, new Gson());

        assertEquals("ultimate_ironman", payload.get("accountType").getAsString());
    }

    @Test
    public void syncPayloadKeepsUnobservedBossKcUnknown() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.bossKcStatus = BossKillCountReader.Result.notLoaded(
            BossKillCountReader.defaultBossCatalog().size(),
            "boss-kill-log-not-observed"
        );

        JsonObject payload = ScapestackSyncPlugin.buildSyncPayload("Lynx Titan", snapshot, new Gson());
        JsonObject coverage = payload.getAsJsonObject("coverage").getAsJsonObject("bossKc");

        assertFalse(payload.has("bossKc"));
        assertEquals("not-loaded", coverage.get("state").getAsString());
        assertEquals("boss-kill-log-not-observed", coverage.get("reason").getAsString());
        assertFalse(coverage.has("capturedAt"));
    }

    @Test
    public void syncPayloadOmitsBankItemsWhenBankSyncIsOff() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();

        JsonObject payload = ScapestackSyncPlugin.buildSyncPayload("Lynx Titan", snapshot, new Gson());

        assertFalse(payload.has("bankItems"));
        assertTrue(payload.has("bankStatus"));
        assertFalse(payload.getAsJsonObject("bankStatus").get("enabled").getAsBoolean());
        assertEquals("opt-in-off", payload.getAsJsonObject("bankStatus").get("unavailableReason").getAsString());
    }

    @Test
    public void syncPayloadCarriesBankStatusWhenCaptured() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.bankItems = Collections.singletonList(new GameStateReader.BankItem(1511, "Logs", 6));
        snapshot.bankStatus = new GameStateReader.BankStatus(true, 1, "2026-07-08T10:00:00Z", null);

        JsonObject payload = ScapestackSyncPlugin.buildSyncPayload("Lynx Titan", snapshot, new Gson());
        JsonObject status = payload.getAsJsonObject("bankStatus");

        assertTrue(status.get("enabled").getAsBoolean());
        assertEquals(1, status.get("itemCount").getAsInt());
        assertEquals("2026-07-08T10:00:00Z", status.get("capturedAt").getAsString());
        assertFalse(status.has("unavailableReason"));
    }

    @Test
    public void syncPayloadCarriesBankUnavailableReasonWhenBankNotOpened() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.bankStatus = new GameStateReader.BankStatus(true, 0, null, "bank-not-opened-this-session");

        JsonObject payload = ScapestackSyncPlugin.buildSyncPayload("Lynx Titan", snapshot, new Gson());
        JsonObject status = payload.getAsJsonObject("bankStatus");

        assertTrue(status.get("enabled").getAsBoolean());
        assertEquals(0, status.get("itemCount").getAsInt());
        assertEquals("bank-not-opened-this-session", status.get("unavailableReason").getAsString());
    }

    @Test
    public void successMessageIncludesVersionAndCoreCounts() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.questsCompleted = Arrays.asList("Cook's Assistant", "Dragon Slayer I");
        snapshot.diariesCompleted = Collections.singletonList(
            new GameStateReader.DiaryCompletion("Karamja", "Easy")
        );
        snapshot.collectionLogItemIds = Arrays.asList(11785, 11787, 12922);
        snapshot.collectionLogStatus = new CollectionLogReader.Status(true, 1, 100, 3);

        assertEquals(
            "Quest and diary progress synced.",
            ScapestackSyncPlugin.buildSyncSuccessMessage(snapshot)
        );
    }

    @Test
    public void successMessagePointsPlayerToNextWithoutShowingUrl() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.questsCompleted = Arrays.asList("Cook's Assistant", "Dragon Slayer I");
        snapshot.diariesCompleted = Collections.singletonList(
            new GameStateReader.DiaryCompletion("Karamja", "Easy")
        );
        snapshot.collectionLogItemIds = Arrays.asList(11785, 11787, 12922);
        snapshot.collectionLogStatus = new CollectionLogReader.Status(true, 1, 100, 3);

        String message = ScapestackSyncPlugin.buildSyncSuccessMessage(
            "Lynx Titan",
            snapshot,
            "https://www.scapestack.org/api/sync"
        );

        assertEquals(
            "Quest and diary progress synced.",
            message
        );
        assertNoPlayerTech(message);
        assertFalse(message.contains("https://"));
        assertFalse(message.contains("?rsn="));
        assertFalse(message.contains("source=plugin-sync"));
        assertFalse(message.contains("/next"));
    }

    @Test
    public void successMessageIncludesSlayerPayloadWhenPresent() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.questsCompleted = Collections.emptyList();
        snapshot.diariesCompleted = Collections.emptyList();
        snapshot.collectionLogItemIds = Arrays.asList(20997, 12073);
        snapshot.collectionLogStatus = new CollectionLogReader.Status(true, 1, 100, 2);
        snapshot.slayer = new GameStateReader.SlayerState(
            132,
            51,
            47,
            19,
            Arrays.asList(1, 2)
        );

        assertEquals(
            "Quest and diary progress synced.",
            ScapestackSyncPlugin.buildSyncSuccessMessage(snapshot)
        );
    }

    @Test
    public void successMessageSaysWhenRuneLiteBankWasSynced() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.bankItems = Collections.singletonList(new GameStateReader.BankItem(1511, "Logs", 6));
        snapshot.bankStatus = new GameStateReader.BankStatus(true, 1, "2026-07-08T10:00:00Z", null);

        String message = ScapestackSyncPlugin.buildSyncSuccessMessage(
            "Lynx Titan",
            snapshot,
            "https://www.scapestack.org/api/sync"
        );

        assertEquals(
            "ScapeStack synced your bank.",
            message
        );
        assertNoPlayerTech(message);
        assertFalse(message.contains("https://"));
    }

    @Test
    public void successMessageCallsOutNewQuestProgressFromServer() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.questsCompleted = Collections.singletonList("Biohazard");
        snapshot.diariesCompleted = Collections.emptyList();
        snapshot.collectionLogItemIds = Collections.emptyList();

        String message = ScapestackSyncPlugin.buildSyncSuccessMessage(
            "Lynx Titan",
            snapshot,
            "https://www.scapestack.org/api/sync",
            "{\"ok\":true,\"syncSummary\":{\"questsCompleted\":[\"Biohazard\"],\"diariesCompleted\":[],\"collectionLogItemIds\":[]}}"
        );

        assertEquals("ScapeStack synced. Next trip updated.", message);
        assertNoPlayerTech(message);
    }

    @Test
    public void successMessageCallsOutIronmanModeWithoutDebugCopy() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.accountType = "ironman";
        snapshot.bankItems = Collections.singletonList(new GameStateReader.BankItem(1511, "Logs", 6));
        snapshot.bankStatus = new GameStateReader.BankStatus(true, 1, "2026-07-08T10:00:00Z", null);

        String message = ScapestackSyncPlugin.buildSyncSuccessMessage(snapshot);

        assertEquals("ScapeStack synced your bank. Ironman mode detected.", message);
        assertNoPlayerTech(message);
    }

    @Test
    public void accountModeLabelsCoverSupportedModes() {
        assertEquals("Normal account", ScapestackSyncPlugin.accountModeLabel("normal"));
        assertEquals("Ironman mode", ScapestackSyncPlugin.accountModeLabel("ironman"));
        assertEquals("Hardcore Ironman mode", ScapestackSyncPlugin.accountModeLabel("hardcore_ironman"));
        assertEquals("Ultimate Ironman mode", ScapestackSyncPlugin.accountModeLabel("ultimate_ironman"));
        assertEquals("Group Ironman mode", ScapestackSyncPlugin.accountModeLabel("group_ironman"));
        assertEquals("Group Ironman mode", ScapestackSyncPlugin.accountModeLabel("hardcore_group_ironman"));
        assertEquals("Account mode unknown", ScapestackSyncPlugin.accountModeLabel(""));
    }

    @Test
    public void successMessageExplainsEmptyCollectionLogSnapshot() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.questsCompleted = Collections.emptyList();
        snapshot.diariesCompleted = Collections.emptyList();
        snapshot.collectionLogItemIds = Collections.emptyList();

        assertEquals(
            "Quest and diary progress synced. Open Collection Log once, then sync again.",
            ScapestackSyncPlugin.buildSyncSuccessMessage(snapshot)
        );
    }

    @Test
    public void successMessageExplainsCollectionLogOpenedWithoutLoadedTabs() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.collectionLogStatus = new CollectionLogReader.Status(true, 1, 0, 0);

        assertEquals(
            "Click a Collection Log category, then sync again.",
            ScapestackSyncPlugin.buildSyncSuccessMessage(
                "Lynx Titan",
                snapshot,
                "https://www.scapestack.org/api/sync"
            )
        );
    }

    @Test
    public void successMessageAllowsZeroObtainedItemsWhenCollectionLogSlotsLoaded() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.collectionLogStatus = new CollectionLogReader.Status(true, 1, 100, 0);

        assertEquals(
            "Quest and diary progress synced.",
            ScapestackSyncPlugin.buildSyncSuccessMessage(
                "Lynx Titan",
                snapshot,
                "https://www.scapestack.org/api/sync"
            )
        );
    }

    @Test
    public void successMessageUsesServerAcceptedCountsWhenAvailable() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.skills = Collections.singletonList(new GameStateReader.SkillLevel("Agility", 35));
        snapshot.questsCompleted = Collections.singletonList("Cook's Assistant");
        snapshot.collectionLogStatus = new CollectionLogReader.Status(true, 1, 100, 0);
        snapshot.bankStatus = new GameStateReader.BankStatus(true, 1, "2026-07-08T10:00:00Z", null);

        assertEquals(
            "ScapeStack synced your bank.",
            ScapestackSyncPlugin.buildSyncSuccessMessage(
                "Lynx Titan",
                snapshot,
                "https://www.scapestack.org/api/sync",
                "{\"ok\":true,\"counts\":{\"skills\":24,\"quests\":180,\"diaries\":44,\"collectionLogItems\":612,\"bankItems\":900}}"
            )
        );
    }

    @Test
    public void successMessagePrioritizesBankInstructionAfterCollectionLogLoaded() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.collectionLogStatus = new CollectionLogReader.Status(true, 1, 100, 0);
        snapshot.bankStatus = new GameStateReader.BankStatus(true, 0, null, "bank-not-opened-this-session");

        assertEquals(
            "Open your bank once, then sync again.",
            ScapestackSyncPlugin.buildSyncSuccessMessage(
                "Lynx Titan",
                snapshot,
                "https://www.scapestack.org/api/sync"
            )
        );
    }

    @Test
    public void nextUrlUsesSelfHostedSyncOrigin() {
        assertEquals(
            "http://127.0.0.1:4173/next?rsn=Iron+Lynx&source=plugin-sync&bank=none",
            ScapestackSyncPlugin.nextUrlFromSyncUrl(
                " http://127.0.0.1:4173/api/sync/claim?debug=1 ",
                " Iron Lynx "
            )
        );
    }

    @Test
    public void nextUrlIgnoresNonHttpSyncOrigin() {
        assertEquals(
            "https://www.scapestack.org/next?rsn=Iron+Lynx&source=plugin-sync&bank=none",
            ScapestackSyncPlugin.nextUrlFromSyncUrl(
                "ftp://example.com/api/sync",
                "Iron Lynx"
            )
        );
    }

    @Test
    public void migratesLegacyScapestackAppEndpointToOrg() {
        assertEquals(
            "https://www.scapestack.org/api/sync",
            ScapestackSyncPlugin.migrateLegacySyncUrl("https://scapestack.app/api/sync")
        );
        assertEquals(
            "https://www.scapestack.org/api/sync",
            ScapestackSyncPlugin.migrateLegacySyncUrl(" https://www.scapestack.app/api/sync/claim?debug=1 ")
        );
    }

    @Test
    public void leavesCurrentAndSelfHostedSyncEndpointsAlone() {
        assertEquals(null, ScapestackSyncPlugin.migrateLegacySyncUrl("https://www.scapestack.org/api/sync"));
        assertEquals(null, ScapestackSyncPlugin.migrateLegacySyncUrl("http://127.0.0.1:4173/api/sync"));
        assertEquals(null, ScapestackSyncPlugin.migrateLegacySyncUrl("https://example.com/api/sync"));
    }

    @Test
    public void autoSyncConfigChangeTriggersImmediateFirstSyncOnlyWhenEnabled() {
        ConfigChanged enabled = configChange("scapestackSync", "autoSync", "true");
        ConfigChanged disabled = configChange("scapestackSync", "autoSync", "false");
        ConfigChanged bankEnabled = configChange("scapestackSync", "syncBankItems", "true");
        ConfigChanged syncNow = configChange("scapestackSync", "syncNow", "true");
        ConfigChanged syncNowReset = configChange("scapestackSync", "syncNow", "false");
        ConfigChanged otherKey = configChange("scapestackSync", "chatFeedback", "true");
        ConfigChanged otherGroup = configChange("banktags", "autoSync", "true");

        assertTrue(ScapestackSyncPlugin.isManualSyncRequest(syncNow));
        assertFalse(ScapestackSyncPlugin.isManualSyncRequest(syncNowReset));
        assertTrue(ScapestackSyncPlugin.shouldSyncAfterConfigChange(enabled));
        assertFalse(ScapestackSyncPlugin.shouldSyncAfterConfigChange(disabled));
        assertFalse(ScapestackSyncPlugin.shouldSyncAfterConfigChange(syncNow));
        assertTrue(ScapestackSyncPlugin.shouldSyncAfterConfigChange(bankEnabled, true));
        assertFalse(ScapestackSyncPlugin.shouldSyncAfterConfigChange(bankEnabled, false));
        assertFalse(ScapestackSyncPlugin.shouldSyncAfterConfigChange(otherKey));
        assertFalse(ScapestackSyncPlugin.shouldSyncAfterConfigChange(otherGroup));
        assertFalse(ScapestackSyncPlugin.shouldSyncAfterConfigChange(null));
    }

    @Test
    public void optInHintOnlyShowsOnceWhenLoggedInAndAutoSyncDisabled() {
        assertTrue(ScapestackSyncPlugin.shouldShowOptInHint(GameState.LOGGED_IN, false, false));
        assertFalse(ScapestackSyncPlugin.shouldShowOptInHint(GameState.LOGGED_IN, true, false));
        assertFalse(ScapestackSyncPlugin.shouldShowOptInHint(GameState.LOGGED_IN, false, true));
        assertFalse(ScapestackSyncPlugin.shouldShowOptInHint(GameState.LOGIN_SCREEN, false, false));
    }

    @Test
    public void optInHintNamesTheSafePayloadScope() {
        assertTrue(ScapestackSyncPlugin.optInHintMessage().contains("ScapeStack is ready"));
        assertTrue(ScapestackSyncPlugin.optInHintMessage().contains("Sync on login"));
        assertNoPlayerTech(ScapestackSyncPlugin.optInHintMessage());
    }

    @Test
    public void configuredSyncUrlDefaultsToOfficialEndpoint() {
        assertEquals(
            "https://www.scapestack.org/api/sync",
            ScapestackSyncPlugin.configuredSyncUrl(null)
        );
    }

    @Test
    public void configuredSyncUrlAllowsHiddenJvmPropertyDevOverride() {
        assertEquals(
            "http://127.0.0.1:4173/api/sync",
            ScapestackSyncPlugin.configuredSyncUrl(" http://127.0.0.1:4173/api/sync/claim?debug=1 ")
        );
        assertEquals(
            "https://www.scapestack.org/api/sync",
            ScapestackSyncPlugin.configuredSyncUrl("")
        );
    }

    @Test
    public void configuredSyncUrlRejectsInvalidHiddenOverride() {
        assertEquals(
            "https://www.scapestack.org/api/sync",
            ScapestackSyncPlugin.configuredSyncUrl("ftp://example.com/api/sync")
        );
    }

    @Test
    public void chatFeedbackStopsAfterPluginShutdown() {
        assertTrue(ScapestackSyncPlugin.shouldQueueChat(true, true, "Scapestack sync started."));
        assertFalse(ScapestackSyncPlugin.shouldQueueChat(true, false, "Scapestack sync complete."));
        assertFalse(ScapestackSyncPlugin.shouldQueueChat(false, true, "Scapestack sync complete."));
        assertFalse(ScapestackSyncPlugin.shouldQueueChat(true, true, ""));
        assertFalse(ScapestackSyncPlugin.shouldQueueChat(true, true, null));
    }

    @Test
    public void manualSyncCooldownPreventsSpam() {
        assertTrue(ScapestackSyncPlugin.manualSyncCooldownElapsed(0, 1000));
        assertFalse(ScapestackSyncPlugin.manualSyncCooldownElapsed(1000, 2000));
        assertTrue(ScapestackSyncPlugin.manualSyncCooldownElapsed(1000, 3500));
    }

    @Test
    public void intervalSyncRequiresOptInLoginAndElapsedTime() {
        assertEquals(15, ScapestackSyncPlugin.normalizedAutoSyncIntervalMinutes(15));
        assertEquals(5, ScapestackSyncPlugin.normalizedAutoSyncIntervalMinutes(1));
        assertEquals(60, ScapestackSyncPlugin.normalizedAutoSyncIntervalMinutes(90));

        assertTrue(ScapestackSyncPlugin.shouldRunIntervalSync(GameState.LOGGED_IN, true, 15, 0, 60_000));
        assertFalse(ScapestackSyncPlugin.shouldRunIntervalSync(GameState.LOGGED_IN, false, 15, 0, 60_000));
        assertFalse(ScapestackSyncPlugin.shouldRunIntervalSync(GameState.LOGIN_SCREEN, true, 15, 0, 60_000));
        assertFalse(ScapestackSyncPlugin.shouldRunIntervalSync(GameState.LOGGED_IN, true, 15, 1000, 14 * 60_000));
        assertTrue(ScapestackSyncPlugin.shouldRunIntervalSync(GameState.LOGGED_IN, true, 15, 1000, 16 * 60_000));
    }

    @Test
    public void lastSyncLabelUsesPlayerFriendlyAge() {
        long now = 10 * 24 * 60 * 60 * 1000L;

        assertEquals("Not synced yet", ScapestackSyncPlugin.formatLastSync(0, now));
        assertEquals("Just now", ScapestackSyncPlugin.formatLastSync(now - 10_000, now));
        assertEquals("5m ago", ScapestackSyncPlugin.formatLastSync(now - 5 * 60_000, now));
        assertEquals("2h ago", ScapestackSyncPlugin.formatLastSync(now - 2 * 60 * 60_000, now));
        assertEquals("3d ago", ScapestackSyncPlugin.formatLastSync(now - 3 * 24 * 60 * 60_000, now));
    }

    @Test
    public void recoveryMessagesGiveActionableNextSteps() {
        assertEquals(
            "ScapeStack needs reconnect. Press Reconnect player, then Sync now.",
            ScapestackSyncPlugin.recoveryMessageForHttpFailure(403, "Token does not match RSN claim")
        );
        assertEquals(
            "ScapeStack is temporarily unavailable. Try again later.",
            ScapestackSyncPlugin.recoveryMessageForHttpFailure(500, "Database unavailable")
        );
        assertEquals(
            "ScapeStack is busy. Wait a moment, then Sync now.",
            ScapestackSyncPlugin.recoveryMessageForHttpFailure(429, "Too many requests")
        );
        assertEquals(
            "ScapeStack could not sync. Open troubleshooting in the plugin panel.",
            ScapestackSyncPlugin.recoveryMessageForHttpFailure(400, "Bad payload")
        );
        assertNoPlayerTech(ScapestackSyncPlugin.recoveryMessageForHttpFailure(403, "Token does not match RSN claim"));
        assertNoPlayerTech(ScapestackSyncPlugin.recoveryMessageForHttpFailure(400, "Bad payload"));
    }

    @Test
    public void panelNextActionUsesPlayerInstructions() {
        assertEquals(
            "Open your bank once, then sync again",
            ScapestackSyncPlugin.panelNextAction(
                new GameStateReader.BankStatus(true, 0, null, "bank-not-opened-this-session"),
                new CollectionLogReader.Status(true, 1, 100, 0),
                "Synced"
            )
        );
        assertEquals(
            "Open Collection Log once, then sync again",
            ScapestackSyncPlugin.panelNextAction(
                new GameStateReader.BankStatus(false, 0, null, "opt-in-off"),
                CollectionLogReader.Status.notOpened(),
                "Synced"
            )
        );
        assertEquals(
            "Click a Collection Log category, then sync again",
            ScapestackSyncPlugin.panelNextAction(
                new GameStateReader.BankStatus(false, 0, null, "opt-in-off"),
                new CollectionLogReader.Status(true, 1, 0, 0),
                "Synced"
            )
        );
        assertEquals(
            "Open next trip in ScapeStack",
            ScapestackSyncPlugin.panelNextAction(
                new GameStateReader.BankStatus(true, 42, "2026-07-09T10:00:00Z", null),
                new CollectionLogReader.Status(true, 1, 100, 5),
                "Synced"
            )
        );
        assertEquals(
            "Open your bank for item checks",
            ScapestackSyncPlugin.panelNextAction(
                new GameStateReader.BankStatus(true, 0, null, null),
                new CollectionLogReader.Status(true, 1, 100, 5),
                "Synced"
            )
        );
    }

    @Test
    public void syncWorkerIsDaemonAndNamedForReviewability() {
        Thread thread = ScapestackSyncPlugin.newSyncThread(() -> {});

        assertEquals("scapestack-sync", thread.getName());
        assertTrue(thread.isDaemon());
    }

    @Test
    public void shutdownCanCancelInFlightSyncCall() {
        RecordingCall call = new RecordingCall();

        ScapestackSyncPlugin.cancelSyncCall(call);
        ScapestackSyncPlugin.cancelSyncCall(null);

        assertTrue(call.cancelled);
    }

    @Test
    public void questCompleteSyncRequiresBothOptIns() {
        String questComplete = "Congratulations! Quest complete! You are awarded 1 Quest point.";

        assertTrue(ScapestackSyncPlugin.shouldSyncAfterQuestComplete(questComplete, true, true));
        assertFalse(ScapestackSyncPlugin.shouldSyncAfterQuestComplete(questComplete, false, true));
        assertFalse(ScapestackSyncPlugin.shouldSyncAfterQuestComplete(questComplete, true, false));
        assertFalse(ScapestackSyncPlugin.shouldSyncAfterQuestComplete("Quest complete!", true, true));
        assertFalse(ScapestackSyncPlugin.shouldSyncAfterQuestComplete(null, true, true));
    }

    private static ConfigChanged configChange(String group, String key, String newValue) {
        ConfigChanged event = new ConfigChanged();
        event.setGroup(group);
        event.setKey(key);
        event.setNewValue(newValue);
        return event;
    }

    private static void assertNoPlayerTech(String message) {
        String lower = message.toLowerCase();
        assertFalse(lower.contains("http"));
        assertFalse(lower.contains("url"));
        assertFalse(lower.contains("endpoint"));
        assertFalse(lower.contains("payload"));
        assertFalse(lower.contains("status code"));
        assertFalse(lower.contains("cl "));
        assertFalse(lower.contains("/next"));
    }

    private static final class RecordingCall implements Call {
        private boolean cancelled;

        @Override
        public Request request() {
            return new Request.Builder().url("https://www.scapestack.org/api/sync").build();
        }

        @Override
        public Response execute() throws IOException {
            throw new IOException("not used");
        }

        @Override
        public void enqueue(Callback responseCallback) {
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isExecuted() {
            return false;
        }

        @Override
        public boolean isCanceled() {
            return cancelled;
        }

        @Override
        public Timeout timeout() {
            return Timeout.NONE;
        }

        @Override
        public Call clone() {
            return new RecordingCall();
        }
    }
}

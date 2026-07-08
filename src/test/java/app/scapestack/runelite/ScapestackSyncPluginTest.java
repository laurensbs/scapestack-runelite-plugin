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
            new GameStateReader.SkillLevel("Agility", 35),
            new GameStateReader.SkillLevel("Ranged", 70)
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
            Arrays.asList(1, 2)
        );

        JsonObject payload = ScapestackSyncPlugin.buildSyncPayload("Lynx Titan", snapshot, new Gson());
        Set<String> fields = payload.keySet();

        assertEquals(new HashSet<>(Arrays.asList(
            "rsn",
            "displayName",
            "pluginVersion",
            "accountType",
            "skills",
            "questsCompleted",
            "diariesCompleted",
            "collectionLogItemIds",
            "collectionLogStatus",
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
            "blocks"
        )), slayer.keySet());
        assertEquals("normal", payload.get("accountType").getAsString());
        assertEquals(2, payload.getAsJsonArray("skills").size());
        assertEquals("Agility", payload.getAsJsonArray("skills").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(35, payload.getAsJsonArray("skills").get(0).getAsJsonObject().get("level").getAsInt());
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
    }

    @Test
    public void syncPayloadCarriesAccountType() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.accountType = "ultimate_ironman";

        JsonObject payload = ScapestackSyncPlugin.buildSyncPayload("Uim Lynx", snapshot, new Gson());

        assertEquals("ultimate_ironman", payload.get("accountType").getAsString());
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
            "Scapestack planner updated: 0 skills, 2 quests, 1 diary, 3 CL items, bank sync off, no Slayer state.",
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
            "Scapestack planner updated for Lynx Titan: 0 skills, 2 quests, 1 diary, 3 CL items, bank sync off, no Slayer state. "
                + "Open Scapestack /next for your session board.",
            message
        );
        assertFalse(message.contains("https://"));
        assertFalse(message.contains("?rsn="));
        assertFalse(message.contains("source=plugin-sync"));
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
            "Scapestack planner updated: 0 skills, 0 quests, 0 diaries, 2 CL items, bank sync off, Slayer 47 left, 132 pts, 51 streak, 2 blocks.",
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
            "Scapestack planner updated for Lynx Titan: 0 skills, 0 quests, 0 diaries, CL not loaded, bank synced: 1 item stack, no Slayer state. "
                + "Open Collection Log, click its tabs, then sync again.",
            message
        );
        assertFalse(message.contains("https://"));
    }

    @Test
    public void successMessageExplainsEmptyCollectionLogSnapshot() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.questsCompleted = Collections.emptyList();
        snapshot.diariesCompleted = Collections.emptyList();
        snapshot.collectionLogItemIds = Collections.emptyList();

        assertEquals(
            "Scapestack planner updated: 0 skills, 0 quests, 0 diaries, CL not loaded, bank sync off, no Slayer state.",
            ScapestackSyncPlugin.buildSyncSuccessMessage(snapshot)
        );
    }

    @Test
    public void successMessageExplainsCollectionLogOpenedWithoutLoadedTabs() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.collectionLogStatus = new CollectionLogReader.Status(true, 1, 0, 0);

        assertEquals(
            "Scapestack planner updated for Lynx Titan: 0 skills, 0 quests, 0 diaries, CL opened, no item slots loaded, bank sync off, no Slayer state. "
                + "Click Collection Log categories/tabs, then sync again.",
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
            "Scapestack planner updated for Lynx Titan: 0 skills, 0 quests, 0 diaries, 0 CL items from loaded CL tabs, bank sync off, no Slayer state. "
                + "Open Scapestack /next for your session board.",
            ScapestackSyncPlugin.buildSyncSuccessMessage(
                "Lynx Titan",
                snapshot,
                "https://www.scapestack.org/api/sync"
            )
        );
    }

    @Test
    public void successMessagePrioritizesBankInstructionAfterCollectionLogLoaded() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.collectionLogStatus = new CollectionLogReader.Status(true, 1, 100, 0);
        snapshot.bankStatus = new GameStateReader.BankStatus(true, 0, null, "bank-not-opened-this-session");

        assertEquals(
            "Scapestack planner updated for Lynx Titan: 0 skills, 0 quests, 0 diaries, 0 CL items from loaded CL tabs, bank not opened this session, no Slayer state. "
                + "Open your bank, then sync again for item checks.",
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
        ConfigChanged otherKey = configChange("scapestackSync", "chatFeedback", "true");
        ConfigChanged otherGroup = configChange("banktags", "autoSync", "true");

        assertTrue(ScapestackSyncPlugin.shouldSyncAfterConfigChange(enabled));
        assertFalse(ScapestackSyncPlugin.shouldSyncAfterConfigChange(disabled));
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
        assertTrue(ScapestackSyncPlugin.optInHintMessage().contains("verified skills, quests, diaries, CL and Slayer"));
        assertTrue(ScapestackSyncPlugin.optInHintMessage().contains("Turn on bank readiness separately"));
    }

    @Test
    public void configuredSyncUrlDefaultsToOfficialEndpoint() {
        assertEquals(
            "https://www.scapestack.org/api/sync",
            ScapestackSyncPlugin.configuredSyncUrl(null, null)
        );
    }

    @Test
    public void configuredSyncUrlAllowsHiddenDevOverride() {
        assertEquals(
            "http://127.0.0.1:4173/api/sync",
            ScapestackSyncPlugin.configuredSyncUrl(" http://127.0.0.1:4173/api/sync/claim?debug=1 ", null)
        );
        assertEquals(
            "http://localhost:4173/api/sync",
            ScapestackSyncPlugin.configuredSyncUrl("", "http://localhost:4173/api/sync")
        );
    }

    @Test
    public void configuredSyncUrlRejectsInvalidHiddenOverride() {
        assertEquals(
            "https://www.scapestack.org/api/sync",
            ScapestackSyncPlugin.configuredSyncUrl("ftp://example.com/api/sync", null)
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

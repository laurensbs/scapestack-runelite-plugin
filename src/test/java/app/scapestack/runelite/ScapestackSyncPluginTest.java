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
        snapshot.diariesCompleted = Collections.singletonList(
            new GameStateReader.DiaryCompletion("Karamja", "Easy")
        );
        snapshot.collectionLogItemIds = Arrays.asList(11785, 11787, 12922);
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
            "questsCompleted",
            "diariesCompleted",
            "collectionLogItemIds",
            "slayer"
        )), fields);

        assertFalse(fields.contains("password"));
        assertFalse(fields.contains("email"));
        assertFalse(fields.contains("bank"));
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
    }

    @Test
    public void successMessageIncludesVersionAndCoreCounts() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.questsCompleted = Arrays.asList("Cook's Assistant", "Dragon Slayer I");
        snapshot.diariesCompleted = Collections.singletonList(
            new GameStateReader.DiaryCompletion("Karamja", "Easy")
        );
        snapshot.collectionLogItemIds = Arrays.asList(11785, 11787, 12922);

        assertEquals(
            "Scapestack v0.2.0 synced: 2 quests, 1 diary, 3 CL items, no Slayer state.",
            ScapestackSyncPlugin.buildSyncSuccessMessage(snapshot)
        );
    }

    @Test
    public void successMessageCanPointPlayerToVerifiedNextPlan() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.questsCompleted = Arrays.asList("Cook's Assistant", "Dragon Slayer I");
        snapshot.diariesCompleted = Collections.singletonList(
            new GameStateReader.DiaryCompletion("Karamja", "Easy")
        );
        snapshot.collectionLogItemIds = Arrays.asList(11785, 11787, 12922);

        assertEquals(
            "Scapestack v0.2.0 synced: 2 quests, 1 diary, 3 CL items, no Slayer state. "
                + "Open verified /next (no bank sent): https://www.scapestack.org/next?rsn=Lynx+Titan&source=plugin-sync&bank=none",
            ScapestackSyncPlugin.buildSyncSuccessMessage(
                "Lynx Titan",
                snapshot,
                "https://www.scapestack.org/api/sync"
            )
        );
    }

    @Test
    public void successMessageIncludesSlayerPayloadWhenPresent() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.questsCompleted = Collections.emptyList();
        snapshot.diariesCompleted = Collections.emptyList();
        snapshot.collectionLogItemIds = Arrays.asList(20997, 12073);
        snapshot.slayer = new GameStateReader.SlayerState(
            132,
            51,
            47,
            19,
            Arrays.asList(1, 2)
        );

        assertEquals(
            "Scapestack v0.2.0 synced: 0 quests, 0 diaries, 2 CL items, Slayer 47 left, 132 pts, 51 streak, 2 blocks.",
            ScapestackSyncPlugin.buildSyncSuccessMessage(snapshot)
        );
    }

    @Test
    public void successMessageExplainsEmptyCollectionLogSnapshot() {
        GameStateReader.Snapshot snapshot = new GameStateReader.Snapshot();
        snapshot.questsCompleted = Collections.emptyList();
        snapshot.diariesCompleted = Collections.emptyList();
        snapshot.collectionLogItemIds = Collections.emptyList();

        assertEquals(
            "Scapestack v0.2.0 synced: 0 quests, 0 diaries, 0 CL items (open Collection Log tabs once), no Slayer state.",
            ScapestackSyncPlugin.buildSyncSuccessMessage(snapshot)
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
    public void autoSyncConfigChangeTriggersImmediateFirstSyncOnlyWhenEnabled() {
        ConfigChanged enabled = configChange("scapestackSync", "autoSync", "true");
        ConfigChanged disabled = configChange("scapestackSync", "autoSync", "false");
        ConfigChanged otherKey = configChange("scapestackSync", "chatFeedback", "true");
        ConfigChanged otherGroup = configChange("banktags", "autoSync", "true");

        assertTrue(ScapestackSyncPlugin.shouldSyncAfterConfigChange(enabled));
        assertFalse(ScapestackSyncPlugin.shouldSyncAfterConfigChange(disabled));
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
        assertTrue(ScapestackSyncPlugin.optInHintMessage().contains("quests, diaries, CL and Slayer only"));
        assertTrue(ScapestackSyncPlugin.optInHintMessage().contains("no bank or inventory"));
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

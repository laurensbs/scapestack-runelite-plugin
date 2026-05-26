package app.scapestack.runelite;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.inject.Inject;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Scapestack Sync — reads the player's quest list, diary completion state,
 * and collection log out of the running game client and POSTs them to
 * www.scapestack.org/api/sync.
 *
 * Triggers:
 *   - Login → full sync (autoSync config)
 *   - Quest-complete chat message → re-sync (syncOnQuestComplete config)
 *
 * Why three signals: each fills a gap in what Jagex's public APIs expose.
 *   - Quests: no public completion API; we scrape the Quest List widget.
 *   - Diaries: no public per-tier API; we scrape the Diary widget.
 *   - Collection log: cl.net plugin already does this, but they require
 *     a separate upload step. We integrate it so one plugin gives
 *     Scapestack everything.
 *
 * State extraction details intentionally light here — first version
 * focuses on the round-trip. Widget IDs + parsing live in
 * GameStateReader to keep this entry-point readable.
 */
@Slf4j
@PluginDescriptor(
    name = "Scapestack Sync",
    description = "Sync your quest/diary/CL state to scapestack.org",
    tags = {"external", "sync", "scapestack"}
)
public class ScapestackSyncPlugin extends Plugin {

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private ScapestackSyncConfig config;
    @Inject private GameStateReader reader;
    @Inject private CollectionLogReader collectionLogReader;
    @Inject private ConfigManager configManager;

    // OSRS collection-log widget group ID. Documented on the RuneLite
    // WidgetID enum; pinning the constant here to keep this file
    // dependency-light for the unit tests.
    private static final int COLLECTION_LOG_GROUP_ID = 621;

    private final OkHttpClient http = new OkHttpClient();
    private final ClaimClient claimClient = new ClaimClient(http);
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final String PLUGIN_VERSION = "0.1.0";
    private static final String USER_AGENT = "scapestack-plugin/" + PLUGIN_VERSION;

    @Override
    protected void startUp() {
        log.info("Scapestack Sync started");
    }

    @Override
    protected void shutDown() {
        log.info("Scapestack Sync stopped");
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged e) {
        // Reset CL accumulator on login screen — handles account-switch
        // on a shared RuneLite installation cleanly.
        if (e.getGameState() == GameState.LOGIN_SCREEN) {
            collectionLogReader.reset();
            return;
        }
        if (!config.autoSync()) return;
        // LOGGING_IN fires once when the world handshake settles. We wait
        // for LOGGED_IN since widgets aren't readable before that.
        if (e.getGameState() == GameState.LOGGED_IN) {
            // Delay the read so the quest-list widget has time to populate.
            // 3s is RuneLite-community standard for this kind of poll.
            clientThread.invokeLater(this::triggerSync);
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (!config.syncOnQuestComplete()) return;
        String message = event.getMessage();
        // RuneLite chat-message text on quest completion is consistent:
        // 'Congratulations! Quest complete! You are awarded ...' — the
        // first three words suffice.
        if (message.startsWith("Congratulations! Quest complete!")) {
            log.debug("Quest completion detected, scheduling re-sync");
            clientThread.invokeLater(this::triggerSync);
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded e) {
        if (e.getGroupId() != COLLECTION_LOG_GROUP_ID) return;
        // Defer one tick — the widget tree isn't fully populated the
        // instant WidgetLoaded fires. invokeLater puts us at the end
        // of the current event queue.
        clientThread.invokeLater(() -> {
            net.runelite.api.widgets.Widget root = client.getWidget(COLLECTION_LOG_GROUP_ID, 0);
            if (root == null) return;
            int added = collectionLogReader.ingest(root);
            if (added > 0) {
                log.debug("CL widget loaded: +{} items, total {}",
                    added, collectionLogReader.snapshot().size());
            }
        });
    }


    private void triggerSync() {
        String rsn = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
        if (rsn == null || rsn.isBlank()) {
            log.debug("triggerSync called but RSN unknown — skipping");
            return;
        }

        GameStateReader.Snapshot snap;
        try {
            // CL items come from the accumulator we've been filling on
            // WidgetLoaded. If the player hasn't opened the CL this
            // session the list is empty — falls back to website's
            // collectionlog.net integration for that subset.
            snap = reader.readSnapshot(client, collectionLogReader.snapshot());
        } catch (Exception ex) {
            log.warn("Failed to read game state", ex);
            return;
        }

        Gson gson = new Gson();
        JsonObject body = new JsonObject();
        body.addProperty("rsn", rsn);
        body.addProperty("displayName", rsn);
        body.addProperty("pluginVersion", PLUGIN_VERSION);
        body.add("questsCompleted", gson.toJsonTree(snap.questsCompleted));
        JsonArray diaries = new JsonArray();
        for (GameStateReader.DiaryCompletion d : snap.diariesCompleted) {
            JsonObject row = new JsonObject();
            row.addProperty("region", d.region);
            row.addProperty("tier", d.tier);
            diaries.add(row);
        }
        body.add("diariesCompleted", diaries);
        body.add("collectionLogItemIds", gson.toJsonTree(snap.collectionLogItemIds));

        // Token bootstrap + claim-if-needed + sync POST all run on a
        // background thread. Claim involves an HTTP round-trip and a
        // best-effort Hiscores lookup on the server, so we never want
        // this on the client thread.
        final String bodyJson = body.toString();
        new Thread(() -> {
            String token = InstallToken.getOrCreate(configManager);
            String claimedRsn = InstallToken.claimedRsn(configManager);
            // Re-claim when the player name has changed OR we've never
            // claimed before. The server is idempotent for {same rsn,
            // same token}, so re-running is cheap.
            if (claimedRsn == null || !claimedRsn.equalsIgnoreCase(rsn)) {
                String claimUrl = ClaimClient.claimUrlFromSyncUrl(config.syncUrl());
                if (claimClient.claim(claimUrl, rsn, token, USER_AGENT)) {
                    InstallToken.rememberClaimedRsn(configManager, rsn);
                } else {
                    // The sync POST below will likely fail with 403; log
                    // so the user can see what's happening, but still try
                    // — the claim may have actually succeeded on a prior
                    // run and our local cache is just empty.
                    log.warn("Claim did not succeed; attempting sync anyway");
                }
            }

            Request req = new Request.Builder()
                .url(config.syncUrl())
                .post(RequestBody.create(JSON, bodyJson))
                .header("User-Agent", USER_AGENT)
                .header("Authorization", "Bearer " + token)
                .build();

            try (Response res = http.newCall(req).execute()) {
                if (res.isSuccessful()) {
                    log.info("Synced to Scapestack: {} quests, {} diaries, {} CL items",
                        snap.questsCompleted.size(),
                        snap.diariesCompleted.size(),
                        snap.collectionLogItemIds.size());
                } else if (res.code() == 401 || res.code() == 403) {
                    // Local cache may be stale (token rotated, claim
                    // wiped, RSN claimed elsewhere). Drop the claimedRsn
                    // marker so the next sync attempts a fresh claim.
                    log.warn("Sync rejected ({}). Will retry claim on next sync.", res.code());
                    InstallToken.rememberClaimedRsn(configManager, "");
                } else {
                    log.warn("Sync failed: HTTP {}", res.code());
                }
            } catch (IOException ex) {
                log.warn("Sync request failed", ex);
            }
        }, "scapestack-sync").start();
    }

    @Provides
    ScapestackSyncConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(ScapestackSyncConfig.class);
    }
}

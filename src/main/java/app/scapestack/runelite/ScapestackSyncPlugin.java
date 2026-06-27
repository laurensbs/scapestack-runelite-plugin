package app.scapestack.runelite;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.inject.Inject;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.HashSet;
import java.util.Set;

/**
 * Scapestack Sync — reads the player's quest list, diary completion state,
 * collection log and Slayer state out of the running game client and POSTs
 * them to www.scapestack.org/api/sync.
 *
 * Triggers:
 *   - Login → full sync (autoSync config)
 *   - Quest-complete chat message → re-sync (syncOnQuestComplete config)
 *
 * Why these signals: each fills a gap in what Jagex's public APIs expose.
 *   - Quests: no public completion API; we scrape the Quest List widget.
 *   - Diaries: no public per-tier API; we scrape the Diary widget.
 *   - Collection log: cl.net plugin already does this, but they require
 *     a separate upload step. We integrate it so one plugin gives
 *     Scapestack everything.
 *   - Slayer: current task, points, streak and blocks are session state;
 *     public trackers cannot reliably infer today's assignment.
 *
 * State extraction details intentionally light here — first version
 * focuses on the round-trip. Widget IDs + parsing live in
 * GameStateReader to keep this entry-point readable.
 */
@Slf4j
@PluginDescriptor(
    name = "Scapestack Sync",
    description = "Sync your quest, diary, collection log and Slayer state to scapestack.org",
    tags = {"external", "sync", "scapestack", "slayer"}
)
public class ScapestackSyncPlugin extends Plugin {

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private ScapestackSyncConfig config;
    @Inject private GameStateReader reader;
    @Inject private CollectionLogReader collectionLogReader;
    @Inject private ConfigManager configManager;
    @Inject private ChatMessageManager chatMessageManager;

    // Shared resources — RuneLite Plugin Hub policy: never construct
    // your own OkHttpClient/Gson, always reuse the injected one. Keeps
    // connection pools + trust-store consistent across plugins.
    @Inject private OkHttpClient http;
    @Inject private Gson gson;

    // OSRS collection-log widget group ID. Documented on the RuneLite
    // WidgetID enum; pinning the constant here to keep this file
    // dependency-light for the unit tests.
    private static final int COLLECTION_LOG_GROUP_ID = 621;
    private static final String CONFIG_GROUP = "scapestackSync";
    private static final String KEY_SYNC_URL = "syncUrl";
    private static final String KEY_AUTO_SYNC = "autoSync";
    private static final String KEY_FORCE_CLAIM = "forceClaimOnNextSync";
    private static final String DEFAULT_SYNC_URL = "https://www.scapestack.org/api/sync";

    private ClaimClient claimClient;
    private SyncServiceReadiness syncServiceReadiness;
    private final SyncGate syncGate = new SyncGate();
    private boolean optInHintShown;
    private volatile boolean running;
    private volatile Call activeCall;
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final String PLUGIN_VERSION = "0.2.0";
    private static final String USER_AGENT = "scapestack-plugin/" + PLUGIN_VERSION;
    private static final String OPT_IN_HINT = "Scapestack Sync installed. Enable Auto-sync on login to send quests, diaries, CL and Slayer only — no bank or inventory.";

    @Override
    protected void startUp() {
        // Wire up the claim-helper here (rather than as a final field)
        // because Guice fills @Inject fields after construction.
        claimClient = new ClaimClient(http);
        syncServiceReadiness = new SyncServiceReadiness(http);
        running = true;
        migrateLegacySyncUrl();
        log.info("Scapestack Sync started");
    }

    @Override
    protected void shutDown() {
        running = false;
        cancelSyncCall(activeCall);
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
        if (shouldShowOptInHint(e.getGameState(), config.autoSync(), optInHintShown)) {
            optInHintShown = true;
            notifyChat(OPT_IN_HINT);
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
    public void onConfigChanged(ConfigChanged event) {
        if (!shouldSyncAfterConfigChange(event)) return;
        if (client.getGameState() != GameState.LOGGED_IN) {
            notifyChat("Scapestack sync enabled. Log in to send your first snapshot.");
            return;
        }

        log.debug("Auto-sync enabled while logged in, scheduling first sync");
        clientThread.invokeLater(this::triggerSync);
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (shouldSyncAfterQuestComplete(
            event.getMessage(),
            config.autoSync(),
            config.syncOnQuestComplete()
        )) {
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

        JsonObject body = buildSyncPayload(rsn, snap, gson);

        // Token bootstrap + claim-if-needed + sync POST all run on a
        // background thread. Claim involves an HTTP round-trip and a
        // best-effort Hiscores lookup on the server, so we never want
        // this on the client thread.
        final String bodyJson = body.toString();
        if (!syncGate.tryStart()) {
            log.debug("Scapestack sync already in flight — skipping duplicate trigger");
            return;
        }
        notifyChat("Scapestack sync started for " + rsn + "…");
        Thread thread = newSyncThread(() -> {
            try {
                String syncUrl = ClaimClient.normalizeSyncUrl(config.syncUrl());
                if (syncUrl.isBlank()) {
                    log.warn("Scapestack sync URL is empty");
                    notifyChat("Scapestack sync failed: Sync URL is empty.");
                    return;
                }
                if (!ClaimClient.isHttpSyncUrl(syncUrl)) {
                    log.warn("Scapestack sync URL must be http(s): {}", syncUrl);
                    notifyChat("Scapestack sync failed: Sync URL must start with http:// or https://.");
                    return;
                }

                if (!running) return;
                SyncServiceReadiness.Result readiness = syncServiceReadiness.check(syncUrl, USER_AGENT, this::setActiveCall);
                if (!running) return;
                if (!readiness.proceed) {
                    log.warn("Scapestack sync service is not ready: {}", readiness.message);
                    notifyChat("Scapestack sync failed: " + readiness.message + ".");
                    return;
                }

                if (config.forceClaimOnNextSync()) {
                    InstallToken.forgetClaim(configManager);
                    configManager.setConfiguration(CONFIG_GROUP, KEY_FORCE_CLAIM, false);
                    notifyChat("Scapestack claim cache cleared. Retrying claim now.");
                }

                String token = InstallToken.getOrCreate(configManager);
                String claimedRsn = InstallToken.claimedRsn(configManager);
                // Re-claim when the player name has changed OR we've never
                // claimed before. The server is idempotent for {same rsn,
                // same token}, so re-running is cheap.
                if (claimedRsn == null || !claimedRsn.equalsIgnoreCase(rsn)) {
                    String claimUrl = ClaimClient.claimUrlFromSyncUrl(syncUrl);
                    if (claimClient.claim(claimUrl, rsn, token, USER_AGENT, this::setActiveCall)) {
                        InstallToken.rememberClaimedRsn(configManager, rsn);
                    } else {
                        // The sync POST below will likely fail with 403; log
                        // so the user can see what's happening, but still try
                        // — the claim may have actually succeeded on a prior
                        // run and our local cache is just empty.
                        log.warn("Claim did not succeed; attempting sync anyway");
                    }
                }
                if (!running) return;

                Request req;
                try {
                    req = new Request.Builder()
                        .url(syncUrl)
                        .post(RequestBody.create(JSON, bodyJson))
                        .header("User-Agent", USER_AGENT)
                        .header("Authorization", "Bearer " + token)
                        .build();
                } catch (IllegalArgumentException ex) {
                    log.warn("Scapestack sync URL is invalid: {}", syncUrl);
                    notifyChat("Scapestack sync failed: invalid Sync URL.");
                    return;
                }

                Call call = http.newCall(req);
                setActiveCall(call);
                try {
                    if (!running) return;
                    try (Response res = call.execute()) {
                        String bodyText = ServerResponseSummary.readBody(res);
                        if (res.isSuccessful()) {
                            log.info("Synced to Scapestack: {} quests, {} diaries, {} CL items",
                                snap.questsCompleted.size(),
                                snap.diariesCompleted.size(),
                                snap.collectionLogItemIds.size());
                            notifyChat(buildSyncSuccessMessage(rsn, snap, syncUrl));
                        } else if (res.code() == 401 || res.code() == 403) {
                            // Local cache may be stale (token rotated, claim
                            // wiped, RSN claimed elsewhere). Drop the claimedRsn
                            // marker so the next sync attempts a fresh claim.
                            String detail = ServerResponseSummary.failureDetail(res.code(), bodyText);
                            log.warn("Sync rejected: {}. Will retry claim on next sync.",
                                ServerResponseSummary.logDetail(res.code(), bodyText));
                            InstallToken.forgetClaim(configManager);
                            notifyChat("Scapestack sync rejected: " + detail + ". Sync again to retry claim.");
                        } else {
                            String detail = ServerResponseSummary.failureDetail(res.code(), bodyText);
                            log.warn("Sync failed: {}", ServerResponseSummary.logDetail(res.code(), bodyText));
                            notifyChat("Scapestack sync failed: " + detail + ".");
                        }
                    }
                } catch (IOException ex) {
                    log.warn("Sync request failed", ex);
                    notifyChat("Scapestack sync failed: connection error.");
                } finally {
                    clearActiveCall(call);
                }
            } finally {
                syncGate.finish();
            }
        });
        thread.start();
    }

    private void setActiveCall(Call call) {
        activeCall = call;
        if (call != null && !running) {
            call.cancel();
        }
    }

    private void clearActiveCall(Call call) {
        if (activeCall == call) {
            activeCall = null;
        }
    }

    private void migrateLegacySyncUrl() {
        String migrated = migrateLegacySyncUrl(config.syncUrl());
        if (migrated != null) {
            configManager.setConfiguration(CONFIG_GROUP, KEY_SYNC_URL, migrated);
            log.info("Migrated Scapestack sync endpoint to {}", migrated);
        }
    }

    private void notifyChat(String message) {
        if (!shouldQueueChat(config.chatFeedback(), running, message)) return;
        clientThread.invokeLater(() -> chatMessageManager.queue(QueuedMessage.builder()
            .type(ChatMessageType.GAMEMESSAGE)
            .value(message)
            .build()));
    }

    static String buildSyncSuccessMessage(GameStateReader.Snapshot snap) {
        return buildSyncSuccessMessage(null, snap, null);
    }

    static String buildSyncSuccessMessage(String rsn, GameStateReader.Snapshot snap, String syncUrl) {
        int questCount = snap.questsCompleted != null ? snap.questsCompleted.size() : 0;
        int diaryCount = snap.diariesCompleted != null ? snap.diariesCompleted.size() : 0;
        int collectionLogCount = snap.collectionLogItemIds != null ? snap.collectionLogItemIds.size() : 0;

        StringBuilder message = new StringBuilder("Scapestack v")
            .append(PLUGIN_VERSION)
            .append(" synced: ")
            .append(formatCount(questCount, "quest", "quests")).append(", ")
            .append(formatCount(diaryCount, "diary", "diaries")).append(", ")
            .append(formatCollectionLogCount(collectionLogCount));

        if (snap.slayer != null) {
            int blockCount = snap.slayer.blocks != null ? snap.slayer.blocks.size() : 0;
            message
                .append(", Slayer ")
                .append(snap.slayer.taskRemaining)
                .append(" left, ")
                .append(snap.slayer.points)
                .append(" pts, ")
                .append(formatCount(snap.slayer.streak, "streak", "streak")).append(", ")
                .append(formatCount(blockCount, "block", "blocks"));
        } else {
            message.append(", no Slayer state");
        }

        if (rsn != null && !rsn.isBlank()) {
            message
                .append(". Open verified /next (no bank sent): ")
                .append(nextUrlFromSyncUrl(syncUrl, rsn));
            return message.toString();
        }

        return message.append('.').toString();
    }

    private static String formatCollectionLogCount(int count) {
        if (count > 0) return formatCount(count, "CL item", "CL items");
        return "0 CL items (open Collection Log tabs once)";
    }

    static String nextUrlFromSyncUrl(String syncUrl, String rsn) {
        String origin = "https://www.scapestack.org";
        String clean = ClaimClient.normalizeSyncUrl(syncUrl);
        if (ClaimClient.isHttpSyncUrl(clean)) {
            try {
                URI uri = URI.create(clean);
                StringBuilder nextOrigin = new StringBuilder()
                    .append(uri.getScheme())
                    .append("://")
                    .append(uri.getHost());
                if (uri.getPort() >= 0) {
                    nextOrigin.append(':').append(uri.getPort());
                }
                origin = nextOrigin.toString();
            } catch (IllegalArgumentException ignored) {
                // Keep production fallback; invalid URL is reported elsewhere.
            }
        }
        return origin + "/next?rsn=" + urlEncode(rsn) + "&source=plugin-sync&bank=none";
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value.trim(), "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            return value.trim().replace(" ", "+");
        }
    }

    static String migrateLegacySyncUrl(String syncUrl) {
        String clean = ClaimClient.normalizeSyncUrl(syncUrl);
        if (clean.isBlank()) return null;

        try {
            URI uri = URI.create(clean);
            String host = uri.getHost();
            String path = uri.getPath();
            if (host != null
                && ("scapestack.app".equalsIgnoreCase(host) || "www.scapestack.app".equalsIgnoreCase(host))
                && "/api/sync".equals(path)) {
                return DEFAULT_SYNC_URL;
            }
        } catch (IllegalArgumentException ignored) {
            return null;
        }

        return null;
    }

    static JsonObject buildSyncPayload(String rsn, GameStateReader.Snapshot snap, Gson gson) {
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
        // Slayer-state: only send it when the plugin could read it.
        if (snap.slayer != null) {
            JsonObject slayer = new JsonObject();
            slayer.addProperty("points", snap.slayer.points);
            slayer.addProperty("streak", snap.slayer.streak);
            slayer.addProperty("taskRemaining", snap.slayer.taskRemaining);
            slayer.addProperty("currentTaskId", snap.slayer.currentTaskId);
            slayer.add("blocks", gson.toJsonTree(snap.slayer.blocks));
            body.add("slayer", slayer);
        }
        return body;
    }

    private static String formatCount(int count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }

    static boolean shouldSyncAfterConfigChange(ConfigChanged event) {
        return event != null
            && CONFIG_GROUP.equals(event.getGroup())
            && KEY_AUTO_SYNC.equals(event.getKey())
            && Boolean.parseBoolean(event.getNewValue());
    }

    static boolean shouldShowOptInHint(GameState gameState, boolean autoSync, boolean alreadyShown) {
        return gameState == GameState.LOGGED_IN && !autoSync && !alreadyShown;
    }

    static boolean shouldQueueChat(boolean chatFeedback, boolean running, String message) {
        return chatFeedback
            && running
            && message != null
            && !message.isBlank();
    }

    static Thread newSyncThread(Runnable task) {
        Thread thread = new Thread(task, "scapestack-sync");
        thread.setDaemon(true);
        return thread;
    }

    static void cancelSyncCall(Call call) {
        if (call != null) {
            call.cancel();
        }
    }

    static boolean shouldSyncAfterQuestComplete(String message, boolean autoSync, boolean syncOnQuestComplete) {
        // RuneLite chat-message text on quest completion is consistent:
        // 'Congratulations! Quest complete! You are awarded ...' — the
        // first three words suffice. The extra sync is gated behind the
        // main auto-sync opt-in so enabling this setting alone never sends
        // a payload.
        return autoSync
            && syncOnQuestComplete
            && message != null
            && message.startsWith("Congratulations! Quest complete!");
    }

    static String optInHintMessage() {
        return OPT_IN_HINT;
    }

    @Provides
    ScapestackSyncConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(ScapestackSyncConfig.class);
    }
}

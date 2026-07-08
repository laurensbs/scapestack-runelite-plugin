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
    description = "Keep Scapestack's OSRS session planner current from RuneLite",
    tags = {"external", "sync", "scapestack", "planner", "quests", "diaries"}
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
    private static final String KEY_SYNC_NOW = "syncNow";
    private static final String KEY_AUTO_SYNC = "autoSync";
    private static final String KEY_SYNC_BANK_ITEMS = "syncBankItems";
    private static final String KEY_FORCE_CLAIM = "forceClaimOnNextSync";
    private static final String DEFAULT_SYNC_URL = "https://www.scapestack.org/api/sync";
    private static final String DEV_SYNC_URL_PROPERTY = "scapestack.syncUrl";
    private static final String DEV_SYNC_URL_ENV = "SCAPESTACK_SYNC_URL";

    private ClaimClient claimClient;
    private SyncServiceReadiness syncServiceReadiness;
    private final SyncGate syncGate = new SyncGate();
    private boolean optInHintShown;
    private volatile boolean running;
    private volatile Call activeCall;
    private long lastManualSyncAtMs;
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final String PLUGIN_VERSION = "0.2.0";
    private static final String USER_AGENT = "scapestack-plugin/" + PLUGIN_VERSION;
    private static final String OPT_IN_HINT = "Scapestack Sync installed. Enable Sync on login for verified skills, quests, diaries, CL and Slayer. Turn on bank readiness separately.";
    private static final long MANUAL_SYNC_COOLDOWN_MS = 2_500L;

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
            clientThread.invokeLater(() -> triggerSync(false));
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (isManualSyncRequest(event)) {
            configManager.setConfiguration(CONFIG_GROUP, KEY_SYNC_NOW, false);
            if (client.getGameState() != GameState.LOGGED_IN) {
                notifyChat("Scapestack: log in, then use Sync now.");
                return;
            }
            log.debug("Manual Scapestack sync requested");
            clientThread.invokeLater(() -> triggerSync(true));
            return;
        }

        if (!shouldSyncAfterConfigChange(event, config.autoSync())) return;
        if (client.getGameState() != GameState.LOGGED_IN) {
            notifyChat("Scapestack sync changed. Log in to send your first snapshot.");
            return;
        }

        log.debug("Scapestack sync config changed while logged in, scheduling sync");
        clientThread.invokeLater(() -> triggerSync(false));
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (shouldSyncAfterQuestComplete(
            event.getMessage(),
            config.autoSync(),
            config.syncOnQuestComplete()
        )) {
            log.debug("Quest completion detected, scheduling re-sync");
            clientThread.invokeLater(() -> triggerSync(false));
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
        triggerSync(false);
    }

    private void triggerSync(boolean manual) {
        String rsn = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
        if (rsn == null || rsn.isBlank()) {
            log.debug("triggerSync called but RSN unknown — skipping");
            if (manual) notifyChat("Scapestack: player name not ready yet. Try again in a moment.");
            return;
        }

        long now = System.currentTimeMillis();
        if (manual && !manualSyncCooldownElapsed(lastManualSyncAtMs, now)) {
            notifyChat("Scapestack: wait a moment before syncing again.");
            return;
        }

        GameStateReader.Snapshot snap;
        try {
            // CL items come from the accumulator we've been filling on
            // WidgetLoaded. If the player hasn't opened the CL this
            // session the list is empty — falls back to website's
            // collectionlog.net integration for that subset.
            snap = reader.readSnapshot(
                client,
                collectionLogReader.snapshot(),
                collectionLogReader.status(),
                config.syncBankItems()
            );
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
            if (manual) notifyChat("Scapestack is already syncing.");
            return;
        }
        if (manual) {
            lastManualSyncAtMs = now;
        }
        notifyChat("Scapestack: updating planner for " + rsn + "...");
        Thread thread = newSyncThread(() -> {
            try {
                String syncUrl = configuredSyncUrl();
                if (syncUrl.isBlank()) {
                    log.warn("Scapestack sync URL is empty");
                    notifyChat("Scapestack needs attention: developer endpoint is empty.");
                    return;
                }
                if (!ClaimClient.isHttpSyncUrl(syncUrl)) {
                    log.warn("Scapestack sync URL must be http(s): {}", syncUrl);
                    notifyChat("Scapestack needs attention: developer endpoint is invalid.");
                    return;
                }

                if (!running) return;
                SyncServiceReadiness.Result readiness = syncServiceReadiness.check(syncUrl, USER_AGENT, this::setActiveCall);
                if (!running) return;
                if (!readiness.proceed) {
                    log.warn("Scapestack sync service is not ready: {}", readiness.message);
                    notifyChat("Scapestack needs attention: " + readiness.message + ".");
                    return;
                }

                if (config.forceClaimOnNextSync()) {
                    InstallToken.forgetClaim(configManager);
                    configManager.setConfiguration(CONFIG_GROUP, KEY_FORCE_CLAIM, false);
                    notifyChat("Scapestack: reconnecting this RuneLite install.");
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
                    notifyChat("Scapestack needs attention: developer endpoint is invalid.");
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
                            notifyChat(buildSyncSuccessMessage(rsn, snap, syncUrl, bodyText));
                        } else if (res.code() == 401 || res.code() == 403) {
                            // Local cache may be stale (token rotated, claim
                            // wiped, RSN claimed elsewhere). Drop the claimedRsn
                            // marker so the next sync attempts a fresh claim.
                            String detail = ServerResponseSummary.failureDetail(res.code(), bodyText);
                            log.warn("Sync rejected: {}. Will retry claim on next sync.",
                                ServerResponseSummary.logDetail(res.code(), bodyText));
                            InstallToken.forgetClaim(configManager);
                            notifyChat(recoveryMessageForHttpFailure(res.code(), detail));
                        } else {
                            String detail = ServerResponseSummary.failureDetail(res.code(), bodyText);
                            log.warn("Sync failed: {}", ServerResponseSummary.logDetail(res.code(), bodyText));
                            notifyChat(recoveryMessageForHttpFailure(res.code(), detail));
                        }
                    }
                } catch (IOException ex) {
                    log.warn("Sync request failed", ex);
                    notifyChat("Scapestack needs attention: check connection, then sync again.");
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
        String migrated = migrateLegacySyncUrl(configManager.getConfiguration(CONFIG_GROUP, KEY_SYNC_URL));
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
        return buildSyncSuccessMessage(rsn, snap, syncUrl, (ServerResponseSummary.AcceptedCounts) null);
    }

    static String buildSyncSuccessMessage(String rsn, GameStateReader.Snapshot snap, String syncUrl, String responseBody) {
        return buildSyncSuccessMessage(rsn, snap, syncUrl, ServerResponseSummary.acceptedCounts(responseBody));
    }

    private static String buildSyncSuccessMessage(
        String rsn,
        GameStateReader.Snapshot snap,
        String syncUrl,
        ServerResponseSummary.AcceptedCounts acceptedCounts
    ) {
        int questCount = acceptedInt(acceptedCounts != null ? acceptedCounts.quests : null, snap.questsCompleted != null ? snap.questsCompleted.size() : 0);
        int skillCount = acceptedInt(acceptedCounts != null ? acceptedCounts.skills : null, snap.skills != null ? snap.skills.size() : 0);
        int diaryCount = acceptedInt(acceptedCounts != null ? acceptedCounts.diaries : null, snap.diariesCompleted != null ? snap.diariesCompleted.size() : 0);
        int collectionLogCount = acceptedInt(acceptedCounts != null ? acceptedCounts.collectionLogItems : null, snap.collectionLogItemIds != null ? snap.collectionLogItemIds.size() : 0);
        GameStateReader.BankStatus bankStatus = effectiveBankStatus(snap);
        if (acceptedCounts != null && acceptedCounts.bankItems != null && acceptedCounts.bankItems > 0 && bankStatus.itemCount != acceptedCounts.bankItems) {
            bankStatus = new GameStateReader.BankStatus(true, acceptedCounts.bankItems, bankStatus.capturedAt, null);
        }
        CollectionLogReader.Status collectionLogStatus = effectiveCollectionLogStatus(snap);

        StringBuilder message = new StringBuilder("Scapestack planner updated")
            .append(rsn != null && !rsn.isBlank() ? " for " + rsn.trim() : "")
            .append(": ")
            .append(formatCount(skillCount, "skill", "skills")).append(", ")
            .append(formatCount(questCount, "quest", "quests")).append(", ")
            .append(formatCount(diaryCount, "diary", "diaries")).append(", ")
            .append(formatCollectionLogStatus(collectionLogCount, collectionLogStatus)).append(", ")
            .append(formatBankStatus(bankStatus));

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
                .append(". ")
                .append(nextPlayerStep(collectionLogStatus, bankStatus));
            return message.toString();
        }

        return message.append('.').toString();
    }

    private static int acceptedInt(Integer accepted, int fallback) {
        return accepted != null ? Math.max(0, accepted) : fallback;
    }

    private static String formatCollectionLogStatus(int count, CollectionLogReader.Status status) {
        if (count > 0) return formatCount(count, "CL item", "CL items");
        if (status == null || !status.opened) {
            return "CL not loaded";
        }
        if (!status.hasLoadedItemSlots()) {
            return "CL opened, no item slots loaded";
        }
        return "0 CL items from loaded CL tabs";
    }

    private static String nextPlayerStep(CollectionLogReader.Status collectionLogStatus, GameStateReader.BankStatus bankStatus) {
        if (collectionLogStatus == null || !collectionLogStatus.opened) {
            return "Open Collection Log, click its tabs, then sync again.";
        }
        if (!collectionLogStatus.hasLoadedItemSlots()) {
            return "Click Collection Log categories/tabs, then sync again.";
        }
        if (bankStatus != null && bankStatus.enabled && bankStatus.itemCount == 0
            && "bank-not-opened-this-session".equals(bankStatus.unavailableReason)) {
            return "Open your bank, then sync again for item checks.";
        }
        return "Open Scapestack /next for your session board.";
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

    private static String configuredSyncUrl() {
        return configuredSyncUrl(
            System.getProperty(DEV_SYNC_URL_PROPERTY),
            System.getenv(DEV_SYNC_URL_ENV)
        );
    }

    static String configuredSyncUrl(String propertyOverride, String envOverride) {
        String override = propertyOverride != null && !propertyOverride.isBlank()
            ? propertyOverride
            : envOverride;
        String candidate = override != null && !override.isBlank()
            ? ClaimClient.normalizeSyncUrl(override)
            : DEFAULT_SYNC_URL;
        if (!ClaimClient.isHttpSyncUrl(candidate)) {
            return DEFAULT_SYNC_URL;
        }
        return candidate;
    }

    static JsonObject buildSyncPayload(String rsn, GameStateReader.Snapshot snap, Gson gson) {
        JsonObject body = new JsonObject();
        body.addProperty("rsn", rsn);
        body.addProperty("displayName", rsn);
        body.addProperty("pluginVersion", PLUGIN_VERSION);
        body.addProperty("accountType", snap.accountType != null && !snap.accountType.isBlank() ? snap.accountType : "normal");
        body.add("questsCompleted", gson.toJsonTree(snap.questsCompleted));
        JsonArray skills = new JsonArray();
        for (GameStateReader.SkillLevel s : snap.skills) {
            JsonObject row = new JsonObject();
            row.addProperty("name", s.name);
            row.addProperty("level", s.level);
            skills.add(row);
        }
        body.add("skills", skills);
        JsonArray diaries = new JsonArray();
        for (GameStateReader.DiaryCompletion d : snap.diariesCompleted) {
            JsonObject row = new JsonObject();
            row.addProperty("region", d.region);
            row.addProperty("tier", d.tier);
            diaries.add(row);
        }
        body.add("diariesCompleted", diaries);
        body.add("collectionLogItemIds", gson.toJsonTree(snap.collectionLogItemIds));
        CollectionLogReader.Status collectionLogStatus = effectiveCollectionLogStatus(snap);
        JsonObject collectionLogStatusJson = new JsonObject();
        collectionLogStatusJson.addProperty("opened", collectionLogStatus.opened);
        collectionLogStatusJson.addProperty("widgetLoads", collectionLogStatus.widgetLoads);
        collectionLogStatusJson.addProperty("lastWidgetItemCount", collectionLogStatus.lastWidgetItemCount);
        collectionLogStatusJson.addProperty("obtainedItemCount", collectionLogStatus.obtainedItemCount);
        body.add("collectionLogStatus", collectionLogStatusJson);
        if (snap.bankItems != null && !snap.bankItems.isEmpty()) {
            JsonArray bankItems = new JsonArray();
            for (GameStateReader.BankItem item : snap.bankItems) {
                JsonObject row = new JsonObject();
                row.addProperty("id", item.id);
                row.addProperty("name", item.name);
                row.addProperty("quantity", item.quantity);
                bankItems.add(row);
            }
            body.add("bankItems", bankItems);
        }
        GameStateReader.BankStatus bankStatus = effectiveBankStatus(snap);
        JsonObject bankStatusJson = new JsonObject();
        bankStatusJson.addProperty("enabled", bankStatus.enabled);
        bankStatusJson.addProperty("itemCount", bankStatus.itemCount);
        if (bankStatus.capturedAt != null && !bankStatus.capturedAt.isBlank()) {
            bankStatusJson.addProperty("capturedAt", bankStatus.capturedAt);
        }
        if (bankStatus.unavailableReason != null && !bankStatus.unavailableReason.isBlank()) {
            bankStatusJson.addProperty("unavailableReason", bankStatus.unavailableReason);
        }
        body.add("bankStatus", bankStatusJson);
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

    private static GameStateReader.BankStatus effectiveBankStatus(GameStateReader.Snapshot snap) {
        int bankCount = snap.bankItems != null ? snap.bankItems.size() : 0;
        if (snap.bankStatus != null
            && bankCount > 0
            && !snap.bankStatus.enabled
            && "opt-in-off".equals(snap.bankStatus.unavailableReason)) {
            return new GameStateReader.BankStatus(true, bankCount, null, null);
        }
        if (snap.bankStatus != null) {
            return snap.bankStatus;
        }
        if (bankCount > 0) {
            return new GameStateReader.BankStatus(true, bankCount, null, null);
        }
        return GameStateReader.BankStatus.optInOff();
    }

    private static CollectionLogReader.Status effectiveCollectionLogStatus(GameStateReader.Snapshot snap) {
        if (snap.collectionLogStatus != null) {
            return snap.collectionLogStatus;
        }
        int collectionLogCount = snap.collectionLogItemIds != null ? snap.collectionLogItemIds.size() : 0;
        return collectionLogCount > 0
            ? new CollectionLogReader.Status(true, 1, collectionLogCount, collectionLogCount)
            : CollectionLogReader.Status.notOpened();
    }

    private static String formatCount(int count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }

    private static String formatBankStatus(GameStateReader.BankStatus status) {
        if (status.itemCount > 0) {
            return "bank synced: " + formatCount(status.itemCount, "item stack", "item stacks");
        }
        if (!status.enabled) {
            return "bank sync off";
        }
        if ("bank-not-opened-this-session".equals(status.unavailableReason)) {
            return "bank not opened this session";
        }
        if ("no-items-captured".equals(status.unavailableReason)) {
            return "bank sync on, no items captured";
        }
        return "bank sync unavailable";
    }

    static boolean shouldSyncAfterConfigChange(ConfigChanged event) {
        return shouldSyncAfterConfigChange(event, false);
    }

    static boolean isManualSyncRequest(ConfigChanged event) {
        return event != null
            && CONFIG_GROUP.equals(event.getGroup())
            && KEY_SYNC_NOW.equals(event.getKey())
            && Boolean.parseBoolean(event.getNewValue());
    }

    static boolean shouldSyncAfterConfigChange(ConfigChanged event, boolean autoSyncEnabled) {
        if (event == null || !CONFIG_GROUP.equals(event.getGroup())) return false;
        if (KEY_AUTO_SYNC.equals(event.getKey())) return Boolean.parseBoolean(event.getNewValue());
        if (KEY_SYNC_BANK_ITEMS.equals(event.getKey())) {
            return autoSyncEnabled && Boolean.parseBoolean(event.getNewValue());
        }
        return false;
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

    static boolean manualSyncCooldownElapsed(long lastManualSyncAtMs, long nowMs) {
        return lastManualSyncAtMs <= 0 || nowMs - lastManualSyncAtMs >= MANUAL_SYNC_COOLDOWN_MS;
    }

    static String recoveryMessageForHttpFailure(int statusCode, String detail) {
        String clean = detail == null || detail.isBlank() ? "HTTP " + statusCode : detail.trim();
        if (statusCode == 401 || statusCode == 403) {
            return "Scapestack needs reconnect: " + clean + ". Use Reconnect player, then Sync now.";
        }
        if (statusCode >= 500) {
            return "Scapestack is temporarily unavailable. Try again later.";
        }
        if (statusCode == 429) {
            return "Scapestack is rate limiting syncs. Wait a moment, then Sync now.";
        }
        return "Scapestack needs attention: " + clean + ".";
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

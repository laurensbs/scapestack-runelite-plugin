package app.scapestack.runelite;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.vars.AccountType;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads quest/diary/CL state out of the live game client.
 *
 * Quests: Client exposes a Quest enum + QuestState per quest — pure
 * API call, no widget scraping needed.
 *
 * Diaries: RuneLite doesn't expose an enum for tier-completion. We
 * read the Achievement Diary widget when it's open and parse the
 * 'task complete' checkmarks. v0 of this plugin: we capture what we
 * see when the player opens the diary screen. Future versions can
 * also scrape the dedicated diary-completion widget that lives in
 * the world map menu.
 *
 * Collection log: same problem — the CL widget is the source of truth.
 * v0 scrapes only when the widget is loaded. Players will need to open
 * the CL once for the data to populate.
 */
@Slf4j
@Singleton
public class GameStateReader {

    @Inject
    private ConfigManager configManager;

    public static class Snapshot {
        public String capturedAt = Instant.now().toString();
        public List<String> questsCompleted = new ArrayList<>();
        public List<SkillLevel> skills = new ArrayList<>();
        public List<DiaryCompletion> diariesCompleted = new ArrayList<>();
        public List<Integer> collectionLogItemIds = new ArrayList<>();
        public CollectionLogReader.Status collectionLogStatus = CollectionLogReader.Status.notOpened();
        public List<BankItem> bankItems = new ArrayList<>();
        public BankStatus bankStatus = BankStatus.optInOff();
        public SlayerState slayer = null;
        public Map<String, Integer> bossKc = null;
        BossKillCountReader.Result bossKcStatus = null;
        public String accountType = "normal";
        public Map<String, PluginSnapshotContract.Domain> coverage = new LinkedHashMap<>();
    }

    public static class BankItem {
        public final int id;
        public final String name;
        public final int quantity;
        public BankItem(int id, String name, int quantity) {
            this.id = id;
            this.name = name;
            this.quantity = quantity;
        }
    }

    public static class BankStatus {
        public final boolean enabled;
        public final int itemCount;
        public final String capturedAt;
        public final String unavailableReason;

        public BankStatus(boolean enabled, int itemCount, String capturedAt, String unavailableReason) {
            this.enabled = enabled;
            this.itemCount = itemCount;
            this.capturedAt = capturedAt;
            this.unavailableReason = unavailableReason;
        }

        public static BankStatus optInOff() {
            return new BankStatus(false, 0, null, "opt-in-off");
        }
    }

    private static class BankSnapshot {
        final List<BankItem> items;
        final BankStatus status;

        BankSnapshot(List<BankItem> items, BankStatus status) {
            this.items = items;
            this.status = status;
        }
    }

    public static class SkillLevel {
        public final String name;
        public final int level;
        public final int xp;
        public SkillLevel(String name, int level) {
            this(name, level, 0);
        }
        public SkillLevel(String name, int level, int xp) {
            this.name = name;
            this.level = level;
            this.xp = xp;
        }
    }

    /** Slayer-state read uit VarPlayers. Null wanneer geen sessie of
     *  varp-leesfout — server-side leeg veld = "geen plugin slayer data". */
    public static class SlayerState {
        public final int points;
        public final int streak;
        public final int taskRemaining;
        public final int currentTaskId;
        public final String taskName;
        public final String taskLocation;
        /** Block-slot task-IDs uit varps 1306..1311. 0 = leeg slot.
         *  Server-side mappen we naar monster.id via een task-id tabel. */
        public final List<Integer> blocks;
        public final List<String> blockNames;
        public SlayerState(int points, int streak, int taskRemaining, int currentTaskId, List<Integer> blocks) {
            this(points, streak, taskRemaining, currentTaskId, null, null, blocks, Collections.emptyList());
        }
        public SlayerState(
            int points,
            int streak,
            int taskRemaining,
            int currentTaskId,
            String taskName,
            String taskLocation,
            List<Integer> blocks,
            List<String> blockNames
        ) {
            this.points = points;
            this.streak = streak;
            this.taskRemaining = taskRemaining;
            this.currentTaskId = currentTaskId;
            this.taskName = taskName;
            this.taskLocation = taskLocation;
            this.blocks = blocks;
            this.blockNames = blockNames;
        }
    }

    public static class DiaryCompletion {
        public final String region;
        public final String tier;
        public DiaryCompletion(String region, String tier) {
            this.region = region;
            this.tier = tier;
        }
    }

    public Snapshot readSnapshot(Client client) {
        Snapshot s = new Snapshot();
        s.questsCompleted = readQuests(client);
        s.skills = readSkills(client);
        s.diariesCompleted = readDiaries(client);
        s.collectionLogItemIds = readCollectionLog(client);
        readBossKillCounts(s);
        s.accountType = readAccountType(client);
        s.coverage = PluginSnapshotContract.observedCoverage(s);
        return s;
    }

    /**
     * Reads quest completion via RuneLite's Quest enum + QuestState API.
     * This is the cleanest of the three signals — no widget scraping.
     */
    private List<String> readQuests(Client client) {
        List<String> out = new ArrayList<>();
        for (Quest q : Quest.values()) {
            try {
                QuestState state = q.getState(client);
                if (state == QuestState.FINISHED) {
                    out.add(q.getName());
                }
            } catch (Exception ex) {
                // Some quests aren't in the player's quest list yet
                // (newer content can throw). Skip silently.
            }
        }
        log.debug("Read {} completed quests", out.size());
        return out;
    }

    /**
     * Reads diary tier completion via the DiaryVarTable + DiaryReader.
     * Each region+tier has a varbit (or legacy varplayer) that the
     * game flips to 1 when the player completes that tier. We walk
     * the table on every sync — cheap (~50 var reads).
     *
     * No widget scraping; vars are the source of truth and they're
     * populated whether or not the player has ever opened the diary
     * interface.
     */
    private List<DiaryCompletion> readDiaries(Client client) {
        DiaryReader reader = new DiaryReader();
        return reader.readFrom(
            (id) -> client.getVarpValue(id),
            (entry) -> entry.isVarbit
                ? client.getVarbitValue(entry.varbitOrVar)
                : client.getVarpValue(entry.varbitOrVar)
        );
    }

    /**
     * Reads the collection-log snapshot accumulated by the
     * CollectionLogReader on every WidgetLoaded event for the CL group.
     * The plugin holds the accumulator across the session — we just
     * return its current snapshot.
     *
     * Limitation: the player must open the CL at least once per
     * session for non-empty data. RuneLite only loads widgets the
     * player actually views.
     */
    private List<Integer> readCollectionLog(Client client) {
        // Threading through the CollectionLogReader singleton is done in
        // the plugin class, not here — this method is called via the
        // Snapshot construction path which has access to both. See
        // ScapestackSyncPlugin#triggerSync for the live wiring.
        return Collections.emptyList();
    }

    /** Test-and-plugin entry-point that takes a pre-collected CL set
     *  instead of relying on a class field. The plugin calls this from
     *  triggerSync with the live reader's snapshot. */
    public Snapshot readSnapshot(Client client, List<Integer> collectionLogItemIds) {
        return readSnapshot(client, collectionLogItemIds, CollectionLogReader.Status.notOpened(), false);
    }

    public Snapshot readSnapshot(
        Client client,
        List<Integer> collectionLogItemIds,
        CollectionLogReader.Status collectionLogStatus
    ) {
        return readSnapshot(client, collectionLogItemIds, collectionLogStatus, false);
    }

    /** Full snapshot path used by the plugin. Bank sync can be turned off
     *  because it can expose wealth/gear. Inventory and equipment are never
     *  read by this plugin. */
    public Snapshot readSnapshot(Client client, List<Integer> collectionLogItemIds, boolean includeBankItems) {
        return readSnapshot(client, collectionLogItemIds, CollectionLogReader.Status.notOpened(), includeBankItems);
    }

    public Snapshot readSnapshot(
        Client client,
        List<Integer> collectionLogItemIds,
        CollectionLogReader.Status collectionLogStatus,
        boolean includeBankItems
    ) {
        Snapshot s = new Snapshot();
        s.questsCompleted = readQuests(client);
        s.skills = readSkills(client);
        s.diariesCompleted = readDiaries(client);
        s.collectionLogItemIds = collectionLogItemIds != null ? collectionLogItemIds : Collections.emptyList();
        s.collectionLogStatus = collectionLogStatus != null
            ? collectionLogStatus
            : CollectionLogReader.Status.notOpened();
        BankSnapshot bank = includeBankItems
            ? readBankSnapshot(client)
            : new BankSnapshot(Collections.emptyList(), BankStatus.optInOff());
        s.bankItems = bank.items;
        s.bankStatus = bank.status;
        s.slayer = readSlayer(client);
        readBossKillCounts(s);
        s.accountType = readAccountType(client);
        s.coverage = PluginSnapshotContract.observedCoverage(s);
        return s;
    }

    private void readBossKillCounts(Snapshot snapshot) {
        BossKillCountReader.Result result = BossKillCountReader.read(configManager, snapshot.capturedAt);
        snapshot.bossKcStatus = result;
        snapshot.bossKc = result.isAvailable()
            ? new LinkedHashMap<>(result.counts)
            : null;
    }

    private List<SkillLevel> readSkills(Client client) {
        List<SkillLevel> out = new ArrayList<>();
        for (Skill skill : Skill.values()) {
            if (skill == Skill.OVERALL) continue;
            try {
                out.add(new SkillLevel(
                    skill.getName(),
                    client.getRealSkillLevel(skill),
                    Math.max(0, client.getSkillExperience(skill))
                ));
            } catch (Exception ex) {
                log.debug("Skill level read failed for {}", skill, ex);
            }
        }
        return out;
    }

    private BankSnapshot readBankSnapshot(Client client) {
        try {
            ItemContainer bank = client.getItemContainer(InventoryID.BANK);
            if (bank == null || bank.getItems() == null) {
                return new BankSnapshot(
                    Collections.emptyList(),
                    new BankStatus(true, 0, null, "bank-not-opened-this-session")
                );
            }
            List<BankItem> items = new ArrayList<>();
            for (Item item : bank.getItems()) {
                if (item == null || item.getId() <= 0 || item.getQuantity() <= 0) continue;
                ItemComposition definition = client.getItemDefinition(item.getId());
                String name = definition != null ? definition.getName() : "";
                if (name == null || name.isBlank() || "null".equalsIgnoreCase(name)) continue;
                items.add(new BankItem(item.getId(), name, item.getQuantity()));
                if (items.size() >= 1200) break;
            }
            String capturedAt = Instant.now().toString();
            return new BankSnapshot(
                items,
                new BankStatus(
                    true,
                    items.size(),
                    capturedAt,
                    null
                )
            );
        } catch (Exception ex) {
            log.debug("Bank item read faalde — bank mogelijk niet geladen", ex);
            return new BankSnapshot(
                Collections.emptyList(),
                new BankStatus(true, 0, null, "bank-not-opened-this-session")
            );
        }
    }

    private String readAccountType(Client client) {
        try {
            return normalizeAccountType(client.getAccountType());
        } catch (Exception ex) {
            log.debug("Account type read faalde — default normal", ex);
            return "normal";
        }
    }

    static String normalizeAccountType(AccountType accountType) {
        if (accountType == null) return "normal";
        switch (accountType) {
            case IRONMAN:
                return "ironman";
            case HARDCORE_IRONMAN:
                return "hardcore_ironman";
            case ULTIMATE_IRONMAN:
                return "ultimate_ironman";
            case GROUP_IRONMAN:
                return "group_ironman";
            case HARDCORE_GROUP_IRONMAN:
                return "hardcore_group_ironman";
            case NORMAL:
            default:
                return "normal";
        }
    }

    /** Slayer points, streak, current task and six block slots. Task identity
     *  is resolved through the same game DB tables RuneLite uses; the raw
     *  target value is retained only for backwards compatibility. */
    private static final int[] BLOCK_SLOT_VARPS = { 1306, 1307, 1308, 1309, 1310, 1311 };

    private SlayerState readSlayer(Client client) {
        try {
            int points = client.getVarbitValue(VarbitID.SLAYER_POINTS);
            int streak = client.getVarbitValue(VarbitID.SLAYER_TASKS_COMPLETED);
            int remaining = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
            int taskId = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
            String taskName = remaining > 0 ? resolveSlayerTaskName(client, taskId) : null;
            String taskLocation = remaining > 0 ? resolveSlayerTaskLocation(client) : null;
            ArrayList<Integer> blocks = new ArrayList<>(BLOCK_SLOT_VARPS.length);
            ArrayList<String> blockNames = new ArrayList<>(BLOCK_SLOT_VARPS.length);
            for (int v : BLOCK_SLOT_VARPS) {
                int id = client.getVarpValue(v);
                if (id > 0) {
                    blocks.add(id);
                    String blockName = resolveSlayerTaskName(client, id);
                    if (blockName != null && !blockName.isBlank()) blockNames.add(blockName);
                }
            }
            return new SlayerState(
                points,
                streak,
                remaining,
                taskId,
                taskName,
                taskLocation,
                blocks,
                blockNames
            );
        } catch (Exception ex) {
            log.debug("Slayer state read faalde — verm. geen sessie", ex);
            return null;
        }
    }

    private String resolveSlayerTaskName(Client client, int taskId) {
        if (taskId <= 0) return null;
        try {
            int taskRow;
            if (taskId == 98) {
                List<Integer> bossRows = client.getDBRowsByValue(
                    DBTableID.SlayerTaskSublist.ID,
                    DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID,
                    0,
                    client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID)
                );
                if (bossRows.isEmpty()) return null;
                Object[] taskFields = client.getDBTableField(
                    bossRows.get(0),
                    DBTableID.SlayerTaskSublist.COL_TASK,
                    0
                );
                if (taskFields.length == 0 || !(taskFields[0] instanceof Integer)) return null;
                taskRow = (Integer) taskFields[0];
            } else {
                List<Integer> taskRows = client.getDBRowsByValue(
                    DBTableID.SlayerTask.ID,
                    DBTableID.SlayerTask.COL_ID,
                    0,
                    taskId
                );
                if (taskRows.isEmpty()) return null;
                taskRow = taskRows.get(0);
            }
            Object[] names = client.getDBTableField(taskRow, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0);
            if (names.length == 0 || !(names[0] instanceof String)) return null;
            String name = ((String) names[0]).trim();
            return name.isEmpty() ? null : name;
        } catch (Exception ex) {
            log.debug("Slayer task name lookup failed for {}", taskId, ex);
            return null;
        }
    }

    private String resolveSlayerTaskLocation(Client client) {
        try {
            int areaId = client.getVarpValue(VarPlayerID.SLAYER_AREA);
            if (areaId <= 0) return null;
            List<Integer> areaRows = client.getDBRowsByValue(
                DBTableID.SlayerArea.ID,
                DBTableID.SlayerArea.COL_AREA_ID,
                0,
                areaId
            );
            if (areaRows.isEmpty()) return null;
            Object[] names = client.getDBTableField(
                areaRows.get(0),
                DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER,
                0
            );
            if (names.length == 0 || !(names[0] instanceof String)) return null;
            String name = ((String) names[0]).trim();
            return name.isEmpty() ? null : name;
        } catch (Exception ex) {
            log.debug("Slayer task location lookup failed", ex);
            return null;
        }
    }
}

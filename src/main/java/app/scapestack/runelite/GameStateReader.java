package app.scapestack.runelite;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;

import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    public static class Snapshot {
        public List<String> questsCompleted = new ArrayList<>();
        public List<DiaryCompletion> diariesCompleted = new ArrayList<>();
        public List<Integer> collectionLogItemIds = new ArrayList<>();
        public SlayerState slayer = null;
    }

    /** Slayer-state read uit VarPlayers. Null wanneer geen sessie of
     *  varp-leesfout — server-side leeg veld = "geen plugin slayer data". */
    public static class SlayerState {
        public final int points;             // varp 1170
        public final int streak;             // varp 1602
        public final int taskRemaining;      // varp 395  (huidige task quantity remaining)
        public final int currentTaskId;      // varp 394  (welke monster is current task)
        /** Block-slot task-IDs uit varps 1306..1311. 0 = leeg slot.
         *  Server-side mappen we naar monster.id via een task-id tabel. */
        public final List<Integer> blocks;
        public SlayerState(int points, int streak, int taskRemaining, int currentTaskId, List<Integer> blocks) {
            this.points = points;
            this.streak = streak;
            this.taskRemaining = taskRemaining;
            this.currentTaskId = currentTaskId;
            this.blocks = blocks;
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
        s.diariesCompleted = readDiaries(client);
        s.collectionLogItemIds = readCollectionLog(client);
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
        Snapshot s = new Snapshot();
        s.questsCompleted = readQuests(client);
        s.diariesCompleted = readDiaries(client);
        s.collectionLogItemIds = collectionLogItemIds != null ? collectionLogItemIds : Collections.emptyList();
        s.slayer = readSlayer(client);
        return s;
    }

    /** Slayer points + streak + current-task remaining + 6 block-slot
     *  task-IDs vanuit VarPlayers. Varp-IDs gepind hier zodat de plugin
     *  test-baar blijft zonder live client. Bron: OSRS Wiki VarPlayer
     *  + Reward Shop pagina.
     *
     *  Block-slot varps: 1306..1311. Lege slot = 0. */
    private static final int[] BLOCK_SLOT_VARPS = { 1306, 1307, 1308, 1309, 1310, 1311 };

    private SlayerState readSlayer(Client client) {
        try {
            int points = client.getVarpValue(1170);
            int streak = client.getVarpValue(1602);
            int remaining = client.getVarpValue(395);
            int taskId = client.getVarpValue(394);
            ArrayList<Integer> blocks = new ArrayList<>(BLOCK_SLOT_VARPS.length);
            for (int v : BLOCK_SLOT_VARPS) {
                int id = client.getVarpValue(v);
                if (id > 0) blocks.add(id);
            }
            // Geen sessie / niet ingelogd → alle vars 0. Treat als "no data."
            if (points == 0 && streak == 0 && remaining == 0 && taskId == 0 && blocks.isEmpty()) {
                return null;
            }
            return new SlayerState(points, streak, remaining, taskId, blocks);
        } catch (Exception ex) {
            log.debug("Slayer state read faalde — verm. geen sessie", ex);
            return null;
        }
    }
}

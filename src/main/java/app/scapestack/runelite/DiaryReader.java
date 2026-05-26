package app.scapestack.runelite;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * Pulls diary completion state out of the live game client by walking
 * the DiaryVarTable and reading each entry's varbit/varplayer value.
 *
 * Logic split: the reader is one class, the lookup table is another.
 * Tests instantiate the reader with a mock value-source (an
 * IntUnaryOperator that returns whatever the test supplies for each
 * id) so we never need a Client.
 */
@Slf4j
@Singleton
public class DiaryReader {

    /** Reads varbit IDs (the common case) and a few legacy varplayers. */
    public List<GameStateReader.DiaryCompletion> read(Client client) {
        return readFrom((id) -> {
            // VarPlayer IDs are typically < 5000; varbits often higher.
            // The table marks which is which.
            return tryRead(client, id);
        }, table -> table.isVarbit
            ? client.getVarbitValue(table.varbitOrVar)
            : client.getVarpValue(table.varbitOrVar));
    }

    private int tryRead(Client client, int id) {
        // Used by tests / fallbacks; real path goes through the BiFunction
        // overload below to keep varbit vs varplayer distinct.
        return client.getVarpValue(id);
    }

    /**
     * Test-friendly entry-point. Takes the resolver that knows how to
     * read a single DiaryVarTable.Entry — so tests can pass a mock
     * map and never construct a Client.
     */
    public List<GameStateReader.DiaryCompletion> readFrom(
            IntUnaryOperator legacyReader, // kept for back-compat with the live-Client path
            EntryReader entryReader) {
        List<GameStateReader.DiaryCompletion> out = new ArrayList<>();
        for (DiaryVarTable.Entry e : DiaryVarTable.ENTRIES) {
            int value;
            try {
                value = entryReader.read(e);
            } catch (Exception ex) {
                // A missing varbit/varplayer happens with stale Wiki data;
                // skip silently so one bad ID doesn't kill the whole sync.
                log.debug("Diary var read failed for {} {}: {}", e.region, e.tier, ex.getMessage());
                continue;
            }
            if (DiaryVarTable.isComplete(e, value)) {
                out.add(new GameStateReader.DiaryCompletion(e.region, DiaryVarTable.tierName(e.tier)));
            }
        }
        log.debug("Read {} completed diary tiers", out.size());
        return out;
    }

    @FunctionalInterface
    public interface EntryReader {
        int read(DiaryVarTable.Entry entry);
    }
}

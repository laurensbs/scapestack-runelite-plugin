package app.scapestack.runelite;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests the DiaryReader path that converts var-values into
 * DiaryCompletion records. We feed the reader a mock value-source so
 * we never construct a RuneLite Client — the same pattern QuestHelper
 * tests use for its diary plugins.
 */
public class DiaryReaderTest {

    /**
     * The simplest case: a maxed-ish account with every varbit set
     * to 1 and every Karamja varplayer past its milestone. We expect
     * all 48 tiers (12 regions × 4) to come back as complete.
     */
    @Test
    public void readsAllCompleteWhenAllVarsSet() {
        DiaryReader reader = new DiaryReader();
        List<GameStateReader.DiaryCompletion> result = reader.readFrom(
            (id) -> 999, // legacy reader; not used by the entryReader path
            (entry) -> entry.isVarbit ? 1 : 999 // any 'past milestone' value
        );
        assertEquals("12 regions × 4 tiers", DiaryVarTable.ENTRIES.size(), result.size());
        // Sample: Karamja Easy should be in there
        assertTrue(result.stream().anyMatch(d -> "Karamja".equals(d.region) && "Easy".equals(d.tier)));
    }

    /**
     * Empty account — no varbits set, no Karamja milestones. We expect
     * zero completions.
     */
    @Test
    public void readsZeroCompleteWhenAllVarsAreZero() {
        DiaryReader reader = new DiaryReader();
        List<GameStateReader.DiaryCompletion> result = reader.readFrom(
            (id) -> 0,
            (entry) -> 0
        );
        assertTrue("zero complete when all vars zero", result.isEmpty());
    }

    /**
     * Mixed account: Karamja Hard done, Falador Easy done, nothing else.
     * Verifies that we don't bleed completions across regions/tiers.
     */
    @Test
    public void readsMixedCompletionsCorrectly() {
        // Build a small map of var-id → observed value
        Map<Integer, Integer> mockVars = new HashMap<>();
        // Karamja Hard: varplayer 3611, milestone 5
        mockVars.put(3611, 5);
        // Falador Easy: varbit 4504, completed = 1
        mockVars.put(4504, 1);

        DiaryReader reader = new DiaryReader();
        List<GameStateReader.DiaryCompletion> result = reader.readFrom(
            (id) -> mockVars.getOrDefault(id, 0),
            (entry) -> mockVars.getOrDefault(entry.varbitOrVar, 0)
        );

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(d -> "Karamja".equals(d.region) && "Hard".equals(d.tier)));
        assertTrue(result.stream().anyMatch(d -> "Falador".equals(d.region) && "Easy".equals(d.tier)));
        // Negative checks: things that should NOT be present
        assertFalse(result.stream().anyMatch(d -> "Karamja".equals(d.region) && "Elite".equals(d.tier)));
        assertFalse(result.stream().anyMatch(d -> "Wilderness".equals(d.region)));
    }

    /**
     * The Karamja varplayer is unusual: it counts up through milestones
     * rather than flipping to 1. A value below the completion threshold
     * should NOT mark the tier as done.
     */
    @Test
    public void karamjaVarplayerHonoursMilestoneThreshold() {
        Map<Integer, Integer> mockVars = new HashMap<>();
        // Karamja Easy completion-value is 5; supply 4 (one short)
        mockVars.put(3578, 4);

        DiaryReader reader = new DiaryReader();
        List<GameStateReader.DiaryCompletion> result = reader.readFrom(
            (id) -> mockVars.getOrDefault(id, 0),
            (entry) -> mockVars.getOrDefault(entry.varbitOrVar, 0)
        );

        assertFalse("Karamja Easy at 4 should NOT be marked done",
            result.stream().anyMatch(d -> "Karamja".equals(d.region) && "Easy".equals(d.tier)));

        // Now bump to 5 — should flip to done
        mockVars.put(3578, 5);
        result = reader.readFrom(
            (id) -> mockVars.getOrDefault(id, 0),
            (entry) -> mockVars.getOrDefault(entry.varbitOrVar, 0)
        );
        assertTrue("Karamja Easy at 5 should be marked done",
            result.stream().anyMatch(d -> "Karamja".equals(d.region) && "Easy".equals(d.tier)));
    }

    /**
     * A bad var read (e.g. id removed from the game) should be skipped
     * silently, not crash the whole sync. We simulate this by throwing
     * from the entry reader for one specific entry and confirming the
     * rest still come back.
     */
    @Test
    public void singleBadVarDoesNotKillTheSync() {
        DiaryReader reader = new DiaryReader();
        List<GameStateReader.DiaryCompletion> result = reader.readFrom(
            (id) -> 1,
            (entry) -> {
                if (entry.varbitOrVar == 4458) {
                    throw new RuntimeException("simulated bad var read");
                }
                return entry.isVarbit ? 1 : 999;
            }
        );
        // 1 entry skipped, the other 47 should still be there
        assertEquals(DiaryVarTable.ENTRIES.size() - 1, result.size());
    }
}

package app.scapestack.runelite;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BossKillCountReaderTest {
    private static final String CAPTURED_AT = "2026-07-18T12:34:56Z";

    @Test
    public void readsOnlyObservedCountsAndPreservesExplicitZero() {
        Map<String, Integer> cache = new HashMap<>();
        cache.put("vorkath", 48);
        cache.put("zulrah", 0);

        BossKillCountReader.Result result = BossKillCountReader.readObservedCounts(
            Arrays.asList("Vorkath", "Zulrah", "Callisto"),
            cache::get,
            CAPTURED_AT
        );

        assertTrue(result.isAvailable());
        assertEquals(3, result.catalogBosses);
        assertEquals(2, result.knownBosses);
        assertEquals(Integer.valueOf(48), result.counts.get("Vorkath"));
        assertEquals(Integer.valueOf(0), result.counts.get("Zulrah"));
        assertFalse(result.counts.containsKey("Callisto"));
        assertEquals("runelite-killcount-cache-observed-only", result.reason);
    }

    @Test
    public void emptyCacheIsNotLoadedInsteadOfZeroKc() {
        BossKillCountReader.Result result = BossKillCountReader.readObservedCounts(
            Arrays.asList("Vorkath", "Zulrah"),
            key -> null,
            CAPTURED_AT
        );

        assertEquals("not-loaded", result.state);
        assertFalse(result.isAvailable());
        assertTrue(result.counts.isEmpty());
        assertEquals("boss-kill-log-not-observed", result.reason);
        assertNull(result.capturedAt);
    }

    @Test
    public void lookupFailureDoesNotTurnUnknownIntoZero() {
        BossKillCountReader.Result result = BossKillCountReader.readObservedCounts(
            Arrays.asList("Vorkath", "Zulrah"),
            key -> {
                if ("vorkath".equals(key)) return 48;
                throw new IllegalStateException("profile unavailable");
            },
            CAPTURED_AT
        );

        assertTrue(result.isAvailable());
        assertEquals(1, result.knownBosses);
        assertEquals(Integer.valueOf(48), result.counts.get("Vorkath"));
        assertFalse(result.counts.containsKey("Zulrah"));
        assertEquals("runelite-killcount-cache-partial", result.reason);
    }

    @Test
    public void allLookupFailuresAreUnavailable() {
        BossKillCountReader.Result result = BossKillCountReader.readObservedCounts(
            Arrays.asList("Vorkath", "Zulrah"),
            key -> { throw new IllegalStateException("profile unavailable"); },
            CAPTURED_AT
        );

        assertEquals("unavailable", result.state);
        assertEquals("boss-killcount-cache-unavailable", result.reason);
        assertTrue(result.counts.isEmpty());
    }

    @Test
    public void catalogIsNormalizedDeduplicatedAndBounded() {
        List<String> names = new ArrayList<>();
        names.add(" Vorkath ");
        names.add("vorkath");
        names.add("");
        for (int index = 0; index < 200; index++) names.add("Boss " + index);

        BossKillCountReader.Result result = BossKillCountReader.readObservedCounts(
            names,
            key -> 1,
            CAPTURED_AT
        );

        assertEquals(BossKillCountReader.MAX_BOSSES, result.catalogBosses);
        assertEquals(BossKillCountReader.MAX_BOSSES, result.knownBosses);
        assertEquals(Integer.valueOf(1), result.counts.get("Vorkath"));
    }

    @Test
    public void pinnedRuneLiteCatalogContainsOnlyBoundedBossRows() {
        List<String> catalog = BossKillCountReader.defaultBossCatalog();

        assertTrue(catalog.size() > 50);
        assertTrue(catalog.size() <= BossKillCountReader.MAX_BOSSES);
        assertTrue(catalog.contains("Vorkath"));
        assertTrue(catalog.contains("Zulrah"));
        assertFalse(catalog.contains("Clue Scrolls (all)"));
        assertFalse(catalog.contains("Last Man Standing"));
    }
}

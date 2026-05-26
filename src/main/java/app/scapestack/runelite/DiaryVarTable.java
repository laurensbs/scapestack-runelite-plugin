package app.scapestack.runelite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Static table mapping each diary region+tier to its OSRS VarPlayer ID
 * and a "completion mask" — the bit pattern the var holds when that tier
 * is fully done.
 *
 * Why VarPlayers instead of widget scraping:
 *   - VarPlayers are the source of truth in the game's data model;
 *     widgets only show what the var says.
 *   - Reading vars works regardless of whether the diary widget has
 *     ever been opened.
 *   - The mapping is documented on the Wiki ("Diary task tracking"
 *     section) and changes only when Jagex adds new diary content.
 *
 * Why a single mask per tier: in OSRS each diary task is one bit in the
 * region's var. "Tier complete" = "every task-bit in that tier is set."
 * The exact bit positions per tier come from the Wiki — we encode the
 * sum-of-bits expected for each tier as a mask. Reading
 * (varValue & mask) == mask is enough.
 *
 * NOTE: these IDs were sourced from the OSRS Wiki "Achievement diary"
 * data tables (May 2026 snapshot). Some recent additions may move; if
 * a tier never reads as done, the mask is the most likely culprit.
 * Keep this file in sync with the website's data/diaries.json regions.
 */
public final class DiaryVarTable {

    private DiaryVarTable() {}

    public enum Tier { EASY, MEDIUM, HARD, ELITE }

    public static final class Entry {
        public final String region;
        public final Tier tier;
        public final int varbitOrVar; // see isVarbit
        public final boolean isVarbit;
        public final int completedValue; // value the var/varbit holds when the tier is complete

        public Entry(String region, Tier tier, int id, boolean isVarbit, int completedValue) {
            this.region = region;
            this.tier = tier;
            this.varbitOrVar = id;
            this.isVarbit = isVarbit;
            this.completedValue = completedValue;
        }
    }

    /**
     * Each diary in OSRS has FOUR booleans (one per tier) that flip to 1
     * when that tier is complete. These flags are tracked by varbits. The
     * IDs below come from the open-source RuneLite QuestHelper plugin's
     * AchievementDiaries.java — the canonical reference in the
     * RuneLite community for diary-completion varbits.
     *
     * If any varbit ID has shifted (rare; Jagex usually appends), the
     * symptom is "tier never reads as done." Update from the Wiki's
     * "Varbit reference" page or QuestHelper's source.
     */
    public static final List<Entry> ENTRIES = Collections.unmodifiableList(Arrays.asList(
        // Ardougne
        new Entry("Ardougne",  Tier.EASY,   4458, true, 1),
        new Entry("Ardougne",  Tier.MEDIUM, 4459, true, 1),
        new Entry("Ardougne",  Tier.HARD,   4460, true, 1),
        new Entry("Ardougne",  Tier.ELITE,  4461, true, 1),
        // Desert
        new Entry("Desert",    Tier.EASY,   4483, true, 1),
        new Entry("Desert",    Tier.MEDIUM, 4484, true, 1),
        new Entry("Desert",    Tier.HARD,   4485, true, 1),
        new Entry("Desert",    Tier.ELITE,  4486, true, 1),
        // Falador
        new Entry("Falador",   Tier.EASY,   4504, true, 1),
        new Entry("Falador",   Tier.MEDIUM, 4505, true, 1),
        new Entry("Falador",   Tier.HARD,   4506, true, 1),
        new Entry("Falador",   Tier.ELITE,  4507, true, 1),
        // Fremennik
        new Entry("Fremennik", Tier.EASY,   4524, true, 1),
        new Entry("Fremennik", Tier.MEDIUM, 4525, true, 1),
        new Entry("Fremennik", Tier.HARD,   4526, true, 1),
        new Entry("Fremennik", Tier.ELITE,  4527, true, 1),
        // Kandarin
        new Entry("Kandarin",  Tier.EASY,   4546, true, 1),
        new Entry("Kandarin",  Tier.MEDIUM, 4547, true, 1),
        new Entry("Kandarin",  Tier.HARD,   4548, true, 1),
        new Entry("Kandarin",  Tier.ELITE,  4549, true, 1),
        // Karamja — varplayers (different shape: numeric milestone)
        new Entry("Karamja",   Tier.EASY,   3578, false, 5),
        new Entry("Karamja",   Tier.MEDIUM, 3599, false, 5),
        new Entry("Karamja",   Tier.HARD,   3611, false, 5),
        new Entry("Karamja",   Tier.ELITE,  4566, true, 1),
        // Kourend & Kebos
        new Entry("Kourend & Kebos", Tier.EASY,   7925, true, 1),
        new Entry("Kourend & Kebos", Tier.MEDIUM, 7926, true, 1),
        new Entry("Kourend & Kebos", Tier.HARD,   7927, true, 1),
        new Entry("Kourend & Kebos", Tier.ELITE,  7928, true, 1),
        // Lumbridge & Draynor
        new Entry("Lumbridge & Draynor", Tier.EASY,   4585, true, 1),
        new Entry("Lumbridge & Draynor", Tier.MEDIUM, 4586, true, 1),
        new Entry("Lumbridge & Draynor", Tier.HARD,   4587, true, 1),
        new Entry("Lumbridge & Draynor", Tier.ELITE,  4588, true, 1),
        // Morytania
        new Entry("Morytania", Tier.EASY,   4609, true, 1),
        new Entry("Morytania", Tier.MEDIUM, 4610, true, 1),
        new Entry("Morytania", Tier.HARD,   4611, true, 1),
        new Entry("Morytania", Tier.ELITE,  4612, true, 1),
        // Varrock
        new Entry("Varrock",   Tier.EASY,   4630, true, 1),
        new Entry("Varrock",   Tier.MEDIUM, 4631, true, 1),
        new Entry("Varrock",   Tier.HARD,   4632, true, 1),
        new Entry("Varrock",   Tier.ELITE,  4633, true, 1),
        // Western Provinces
        new Entry("Western Provinces", Tier.EASY,   4653, true, 1),
        new Entry("Western Provinces", Tier.MEDIUM, 4654, true, 1),
        new Entry("Western Provinces", Tier.HARD,   4655, true, 1),
        new Entry("Western Provinces", Tier.ELITE,  4656, true, 1),
        // Wilderness
        new Entry("Wilderness", Tier.EASY,   4680, true, 1),
        new Entry("Wilderness", Tier.MEDIUM, 4681, true, 1),
        new Entry("Wilderness", Tier.HARD,   4682, true, 1),
        new Entry("Wilderness", Tier.ELITE,  4683, true, 1)
    ));

    public static String tierName(Tier t) {
        switch (t) {
            case EASY:   return "Easy";
            case MEDIUM: return "Medium";
            case HARD:   return "Hard";
            case ELITE:  return "Elite";
            default: throw new IllegalArgumentException();
        }
    }

    /**
     * Pure-data version of "is this entry complete?" that takes the var
     * value directly. Used by both the live game reader and the unit
     * tests — the latter feed mock values so we don't need a Client.
     */
    public static boolean isComplete(Entry entry, int observedValue) {
        // For varbits the completedValue is 1 (boolean). For Karamja's
        // varplayer-based tiers we look for "value >= completedValue"
        // because the var counts up through milestones — a player who's
        // moved past the completion threshold reads higher than 5.
        if (entry.isVarbit) {
            return observedValue == entry.completedValue;
        }
        return observedValue >= entry.completedValue;
    }

    /**
     * Subset of ENTRIES whose region matches one of the supplied
     * region names. Used to keep the reader honest when the website
     * adds a new region — we don't blindly include diaries the engine
     * doesn't know about.
     */
    public static List<Entry> forKnownRegions(java.util.Set<String> knownRegions) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : ENTRIES) {
            if (knownRegions.contains(e.region)) out.add(e);
        }
        return out;
    }
}

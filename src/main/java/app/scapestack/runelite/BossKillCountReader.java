package app.scapestack.runelite;

import net.runelite.client.config.ConfigManager;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.HiscoreSkillType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reads only boss counts that RuneLite has actually observed for this profile. */
final class BossKillCountReader {
    static final int MAX_BOSSES = 128;
    private static final int MAX_BOSS_NAME_LENGTH = 80;
    private static final String CONFIG_GROUP = "killcount";

    @FunctionalInterface
    interface KillCountLookup {
        Integer get(String key) throws Exception;
    }

    static final class Result {
        final String state;
        final Map<String, Integer> counts;
        final int knownBosses;
        final int catalogBosses;
        final String capturedAt;
        final String reason;

        private Result(
            String state,
            Map<String, Integer> counts,
            int knownBosses,
            int catalogBosses,
            String capturedAt,
            String reason
        ) {
            this.state = state;
            this.counts = counts == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(counts));
            this.knownBosses = knownBosses;
            this.catalogBosses = catalogBosses;
            this.capturedAt = capturedAt;
            this.reason = reason;
        }

        static Result available(
            Map<String, Integer> counts,
            int catalogBosses,
            String capturedAt,
            int lookupFailures
        ) {
            int knownBosses = counts != null ? counts.size() : 0;
            String reason = lookupFailures > 0
                ? "runelite-killcount-cache-partial"
                : "runelite-killcount-cache-observed-only";
            return new Result("available", counts, knownBosses, catalogBosses, capturedAt, reason);
        }

        static Result notLoaded(int catalogBosses, String reason) {
            return new Result("not-loaded", null, 0, catalogBosses, null, reason);
        }

        static Result unavailable(String reason) {
            return new Result("unavailable", null, 0, 0, null, reason);
        }

        boolean isAvailable() {
            return "available".equals(state);
        }
    }

    private BossKillCountReader() {}

    static Result read(ConfigManager configManager, String capturedAt) {
        if (configManager == null) {
            return Result.unavailable("boss-killcount-cache-unavailable");
        }

        List<String> bossNames = defaultBossCatalog();
        if (bossNames.isEmpty()) {
            return Result.unavailable("boss-catalog-unavailable");
        }

        return readObservedCounts(
            bossNames,
            key -> configManager.getRSProfileConfiguration(CONFIG_GROUP, key, Integer.TYPE),
            capturedAt
        );
    }

    static Result readObservedCounts(
        List<String> bossNames,
        KillCountLookup lookup,
        String capturedAt
    ) {
        List<String> catalog = normalizeCatalog(bossNames);
        if (catalog.isEmpty()) {
            return Result.unavailable("boss-catalog-unavailable");
        }
        if (lookup == null) {
            return Result.unavailable("boss-killcount-cache-unavailable");
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        int lookupFailures = 0;
        for (String name : catalog) {
            Integer count;
            try {
                count = lookup.get(name.toLowerCase(Locale.ROOT));
            } catch (Exception ignored) {
                lookupFailures++;
                continue;
            }
            if (count == null) continue;
            if (count < 0) {
                lookupFailures++;
                continue;
            }
            counts.put(name, count);
        }

        if (counts.isEmpty()) {
            if (lookupFailures >= catalog.size()) {
                return Result.unavailable("boss-killcount-cache-unavailable");
            }
            return Result.notLoaded(catalog.size(), "boss-kill-log-not-observed");
        }
        return Result.available(counts, catalog.size(), capturedAt, lookupFailures);
    }

    static List<String> defaultBossCatalog() {
        List<String> names = new ArrayList<>();
        for (HiscoreSkill skill : HiscoreSkill.values()) {
            if (skill.getType() != HiscoreSkillType.BOSS) continue;
            names.add(skill.getName());
            if (names.size() >= MAX_BOSSES) break;
        }
        return normalizeCatalog(names);
    }

    private static List<String> normalizeCatalog(List<String> bossNames) {
        if (bossNames == null || bossNames.isEmpty()) return Collections.emptyList();
        Set<String> seen = new LinkedHashSet<>();
        List<String> normalized = new ArrayList<>();
        for (String rawName : bossNames) {
            if (normalized.size() >= MAX_BOSSES) break;
            if (rawName == null) continue;
            String name = rawName.trim();
            if (name.isEmpty() || name.length() > MAX_BOSS_NAME_LENGTH) continue;
            String key = name.toLowerCase(Locale.ROOT);
            if (seen.add(key)) normalized.add(name);
        }
        return normalized;
    }
}

package app.scapestack.runelite;

import com.google.gson.JsonObject;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned metadata that keeps missing RuneLite domains explicit. */
final class PluginSnapshotContract {
    static final int VERSION = 3;
    static final List<String> DOMAINS = Arrays.asList(
        "skills",
        "quests",
        "diaries",
        "collectionLog",
        "bossKc",
        "slayer",
        "accountMode",
        "bank"
    );

    static final class Domain {
        final String state;
        final String capturedAt;
        final String reason;

        Domain(String state, String capturedAt, String reason) {
            this.state = state;
            this.capturedAt = capturedAt;
            this.reason = reason;
        }

        static Domain available(String capturedAt) {
            return available(capturedAt, null);
        }

        static Domain available(String capturedAt, String reason) {
            return new Domain("available", validTimestamp(capturedAt), reason);
        }

        static Domain unavailable(String reason) {
            return new Domain("unavailable", null, reason);
        }

        static Domain permissionOff(String reason) {
            return new Domain("permission-off", null, reason);
        }

        static Domain notLoaded(String reason) {
            return new Domain("not-loaded", null, reason);
        }

        static Domain unsupported(String reason) {
            return new Domain("unsupported", null, reason);
        }
    }

    private PluginSnapshotContract() {}

    static Map<String, Domain> observedCoverage(GameStateReader.Snapshot snapshot) {
        String capturedAt = validTimestamp(snapshot.capturedAt);
        Map<String, Domain> coverage = new LinkedHashMap<>();
        coverage.put("skills", snapshot.skills != null && !snapshot.skills.isEmpty()
            ? Domain.available(capturedAt)
            : Domain.unavailable("skills-unavailable"));
        coverage.put("quests", Domain.available(capturedAt));
        coverage.put("diaries", Domain.available(capturedAt));

        CollectionLogReader.Status collectionLog = snapshot.collectionLogStatus;
        if (collectionLog != null && collectionLog.hasLoadedItemSlots()) {
            coverage.put("collectionLog", Domain.available(
                firstTimestamp(collectionLog.capturedAt, capturedAt),
                "loaded-categories-only"
            ));
        } else if (collectionLog != null && collectionLog.opened) {
            coverage.put("collectionLog", Domain.notLoaded("collection-log-category-not-loaded"));
        } else {
            coverage.put("collectionLog", Domain.notLoaded("collection-log-not-opened"));
        }

        coverage.put("bossKc", bossKcCoverage(snapshot, capturedAt));
        coverage.put("slayer", snapshot.slayer != null
            ? Domain.available(capturedAt)
            : Domain.unavailable("slayer-vars-unavailable"));
        coverage.put("accountMode", snapshot.accountType != null && !snapshot.accountType.isBlank()
            ? Domain.available(capturedAt)
            : Domain.unavailable("account-mode-unavailable"));
        coverage.put("bank", bankCoverage(snapshot, capturedAt));
        return coverage;
    }

    static Map<String, Domain> completeCoverage(GameStateReader.Snapshot snapshot) {
        Map<String, Domain> coverage = observedCoverage(snapshot);
        if (snapshot.coverage != null) {
            for (String domain : DOMAINS) {
                Domain explicit = snapshot.coverage.get(domain);
                if (explicit != null) coverage.put(domain, explicit);
            }
        }
        return coverage;
    }

    static JsonObject coverageJson(GameStateReader.Snapshot snapshot) {
        JsonObject result = new JsonObject();
        for (Map.Entry<String, Domain> entry : completeCoverage(snapshot).entrySet()) {
            Domain domain = entry.getValue();
            JsonObject row = new JsonObject();
            row.addProperty("state", domain.state);
            if (domain.capturedAt != null) row.addProperty("capturedAt", domain.capturedAt);
            if (domain.reason != null && !domain.reason.isBlank()) row.addProperty("reason", domain.reason);
            result.add(entry.getKey(), row);
        }
        return result;
    }

    private static Domain bankCoverage(GameStateReader.Snapshot snapshot, String fallbackCapturedAt) {
        GameStateReader.BankStatus status = snapshot.bankStatus;
        if (status == null) return Domain.unavailable("bank-status-unavailable");
        if (!status.enabled && snapshot.bankItems != null && !snapshot.bankItems.isEmpty()) {
            return Domain.available(fallbackCapturedAt);
        }
        if (!status.enabled) return Domain.permissionOff("bank-sync-disabled");
        if ("bank-not-opened-this-session".equals(status.unavailableReason)) {
            return Domain.notLoaded("bank-not-opened-this-session");
        }
        if (status.unavailableReason != null && !status.unavailableReason.isBlank()) {
            return Domain.unavailable(status.unavailableReason);
        }
        return Domain.available(firstTimestamp(status.capturedAt, fallbackCapturedAt));
    }

    private static Domain bossKcCoverage(GameStateReader.Snapshot snapshot, String fallbackCapturedAt) {
        BossKillCountReader.Result status = snapshot.bossKcStatus;
        if (status == null) {
            return snapshot.bossKc != null
                ? Domain.available(fallbackCapturedAt, "observed-boss-kill-counts")
                : Domain.notLoaded("boss-kill-counts-not-read");
        }
        if ("available".equals(status.state)) {
            return Domain.available(firstTimestamp(status.capturedAt, fallbackCapturedAt), status.reason);
        }
        if ("not-loaded".equals(status.state)) {
            return Domain.notLoaded(status.reason);
        }
        return Domain.unavailable(status.reason);
    }

    private static String firstTimestamp(String preferred, String fallback) {
        String validPreferred = validTimestamp(preferred);
        return validPreferred != null ? validPreferred : validTimestamp(fallback);
    }

    private static String validTimestamp(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value).toString();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}

package app.scapestack.runelite;

import net.runelite.client.config.ConfigManager;

import java.util.UUID;

/**
 * Per-install secret used to bind a RuneLite installation to an RSN.
 *
 * Lifecycle:
 *   1. First plugin run: generate a UUID, persist via ConfigManager under
 *      ("scapestackSync", "installToken").
 *   2. Plugin's first sync POSTs {rsn} plus Authorization: Bearer <token>
 *      to /api/sync/claim. The server stores sha256(token) keyed by RSN,
 *      first-write-wins.
 *   3. Every subsequent /api/sync POST carries Authorization: Bearer <token>.
 *      Server rejects when the hash doesn't match the bound claim.
 *
 * The token is generated client-side so we never have to ship a secret
 * through any out-of-band channel; only this install knows it.
 *
 * Threat model + caveats live in src/lib/sync-auth.ts on the server side.
 *
 * Test seam: the bottom-half helpers accept a {@link KeyValueStore} so
 * unit tests can substitute an in-memory map for ConfigManager (which
 * needs a full RuneLite DI context to instantiate). The public overloads
 * adapt ConfigManager → KeyValueStore so plugin code stays unchanged.
 */
public final class InstallToken {

    private static final String GROUP = "scapestackSync";
    private static final String KEY_TOKEN   = "installToken";
    private static final String KEY_CLAIMED = "claimedRsn";

    private InstallToken() {}

    /** Anything that can persist {key → string-value} pairs. */
    public interface KeyValueStore {
        String get(String key);
        void set(String key, String value);
    }

    /** Wraps a ConfigManager so the plugin call-sites need no changes. */
    static KeyValueStore wrap(ConfigManager cm) {
        return new KeyValueStore() {
            @Override public String get(String key) {
                return cm.getConfiguration(GROUP, key);
            }
            @Override public void set(String key, String value) {
                cm.setConfiguration(GROUP, key, value);
            }
        };
    }

    // ---------- public API used by ScapestackSyncPlugin ----------

    public static String getOrCreate(ConfigManager cm)          { return getOrCreate(wrap(cm)); }
    public static String claimedRsn(ConfigManager cm)           { return claimedRsn(wrap(cm)); }
    public static void rememberClaimedRsn(ConfigManager cm, String rsn) { rememberClaimedRsn(wrap(cm), rsn); }
    public static void forgetClaim(ConfigManager cm)            { forgetClaim(wrap(cm)); }

    // ---------- test-facing overloads ----------

    public static String getOrCreate(KeyValueStore store) {
        String existing = store.get(KEY_TOKEN);
        if (existing != null && !existing.isBlank()) {
            return existing.trim();
        }
        String fresh = UUID.randomUUID().toString();
        store.set(KEY_TOKEN, fresh);
        return fresh;
    }

    public static String claimedRsn(KeyValueStore store) {
        String v = store.get(KEY_CLAIMED);
        return v == null || v.isBlank() ? null : v.trim();
    }

    public static void rememberClaimedRsn(KeyValueStore store, String rsn) {
        store.set(KEY_CLAIMED, rsn);
    }

    public static void forgetClaim(KeyValueStore store) {
        store.set(KEY_CLAIMED, "");
    }
}

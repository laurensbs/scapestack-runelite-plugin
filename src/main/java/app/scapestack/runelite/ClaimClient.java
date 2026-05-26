package app.scapestack.runelite;

import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;

/**
 * Thin client for POST /api/sync/claim.
 *
 * Separate from the main sync POST because the claim is a one-shot per
 * (install, rsn) pair — once it succeeds we cache the RSN locally and
 * never call it again. Pulled out so {@link ScapestackSyncPlugin} stays
 * focused on the per-tick sync hot path.
 */
@Slf4j
public final class ClaimClient {

    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient http;

    public ClaimClient(OkHttpClient http) {
        this.http = http;
    }

    /**
     * POSTs the claim. Returns true on a 2xx response, false on anything
     * else. We treat the call as best-effort: a 409 (RSN already claimed
     * by another install) leaves the sync POST to fail with 403, which
     * the user will see in the log.
     *
     * The {@code claimUrl} is derived from the sync endpoint by replacing
     * the trailing /sync with /sync/claim — keeps one config field.
     */
    public boolean claim(String claimUrl, String rsn, String token, String userAgent) {
        JsonObject body = new JsonObject();
        body.addProperty("rsn", rsn);
        body.addProperty("token", token);

        Request req = new Request.Builder()
            .url(claimUrl)
            .post(RequestBody.create(JSON, body.toString()))
            .header("User-Agent", userAgent)
            .build();

        try (Response res = http.newCall(req).execute()) {
            if (res.isSuccessful()) {
                log.info("Scapestack claim ok for {}", rsn);
                return true;
            }
            log.warn("Scapestack claim failed: HTTP {} for {}", res.code(), rsn);
            return false;
        } catch (IOException ex) {
            log.warn("Scapestack claim request failed", ex);
            return false;
        }
    }

    /**
     * Derives the claim endpoint URL from the configured sync URL.
     * Public for the unit test.
     */
    public static String claimUrlFromSyncUrl(String syncUrl) {
        if (syncUrl == null) return "";
        if (syncUrl.endsWith("/sync")) return syncUrl + "/claim";
        if (syncUrl.endsWith("/sync/")) return syncUrl + "claim";
        // Fallback: append /claim. The plugin's default points at
        // /api/sync so this branch is mostly defensive.
        return syncUrl + (syncUrl.endsWith("/") ? "" : "/") + "claim";
    }
}

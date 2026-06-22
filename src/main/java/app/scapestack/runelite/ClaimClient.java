package app.scapestack.runelite;

import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.net.URI;
import java.util.function.Consumer;

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
        return claim(claimUrl, rsn, token, userAgent, null);
    }

    boolean claim(String claimUrl, String rsn, String token, String userAgent, Consumer<Call> activeCall) {
        JsonObject body = new JsonObject();
        body.addProperty("rsn", rsn);

        try {
            Request req = new Request.Builder()
                .url(claimUrl)
                .post(RequestBody.create(JSON, body.toString()))
                .header("User-Agent", userAgent)
                .header("Authorization", "Bearer " + token)
                .build();
            Call call = http.newCall(req);
            updateActiveCall(activeCall, call);
            try (Response res = call.execute()) {
                String bodyText = ServerResponseSummary.readBody(res);
                if (res.isSuccessful()) {
                    log.info("Scapestack claim ok for {}", rsn);
                    return true;
                }
                log.warn("Scapestack claim failed for {}: {}",
                    rsn,
                    ServerResponseSummary.logDetail(res.code(), bodyText));
                return false;
            } finally {
                updateActiveCall(activeCall, null);
            }
        } catch (IllegalArgumentException ex) {
            log.warn("Scapestack claim URL is invalid: {}", claimUrl);
            return false;
        } catch (IOException ex) {
            log.warn("Scapestack claim request failed", ex);
            return false;
        }
    }

    private static void updateActiveCall(Consumer<Call> activeCall, Call call) {
        if (activeCall != null) {
            activeCall.accept(call);
        }
    }

    /**
     * Normalizes a user-configured sync endpoint before OkHttp sees it.
     * RuneLite config text fields are easy to paste with whitespace; query
     * strings and fragments also break claim URL derivation because the claim
     * route is path-based. Keep this conservative: trim, strip ?/# suffixes,
     * collapse trailing slashes, and recover when a user pasted the derived
     * /sync/claim endpoint instead of the base /sync endpoint.
     */
    public static String normalizeSyncUrl(String syncUrl) {
        if (syncUrl == null) return "";
        String clean = syncUrl.trim();
        int query = clean.indexOf('?');
        int fragment = clean.indexOf('#');
        int cut = -1;
        if (query >= 0) cut = query;
        if (fragment >= 0 && (cut < 0 || fragment < cut)) cut = fragment;
        if (cut >= 0) clean = clean.substring(0, cut);
        while (clean.endsWith("/") && !clean.endsWith("://")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.endsWith("/sync/claim")) {
            clean = clean.substring(0, clean.length() - "/claim".length());
        }
        return clean;
    }

    /**
     * Derives the claim endpoint URL from the configured sync URL.
     * Public for the unit test.
     */
    public static String claimUrlFromSyncUrl(String syncUrl) {
        String clean = normalizeSyncUrl(syncUrl);
        if (clean.isEmpty()) return "";
        if (clean.endsWith("/sync")) return clean + "/claim";
        return clean + "/claim";
    }

    /**
     * Returns true only for network endpoints OkHttp can POST to and the
     * plugin can safely echo back as a browser handoff origin.
     */
    public static boolean isHttpSyncUrl(String syncUrl) {
        String clean = normalizeSyncUrl(syncUrl);
        if (clean.isEmpty()) return false;
        try {
            URI uri = URI.create(clean);
            String scheme = uri.getScheme();
            return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                && uri.getHost() != null
                && !uri.getHost().isBlank();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}

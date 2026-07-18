package app.scapestack.runelite;

import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;

/** Approves a short browser pairing code using the existing install claim. */
@Slf4j
final class PairingClient {
    private static final MediaType JSON = MediaType.parse("application/json");
    private final OkHttpClient http;

    PairingClient(OkHttpClient http) {
        this.http = http;
    }

    boolean approve(String syncUrl, String rsn, String code, String token, String userAgent) {
        JsonObject body = new JsonObject();
        body.addProperty("rsn", rsn);
        body.addProperty("code", normalizeCode(code));
        try {
            Request request = new Request.Builder()
                .url(pairingUrlFromSyncUrl(syncUrl))
                .post(RequestBody.create(JSON, body.toString()))
                .header("User-Agent", userAgent)
                .header("Authorization", "Bearer " + token)
                .build();
            try (Response response = http.newCall(request).execute()) {
                String responseBody = ServerResponseSummary.readBody(response);
                if (response.isSuccessful()) return true;
                log.warn("Scapestack browser connection failed: {}",
                    ServerResponseSummary.logDetail(response.code(), responseBody));
                return false;
            }
        } catch (IllegalArgumentException ex) {
            log.warn("Scapestack browser connection URL is invalid");
            return false;
        } catch (IOException ex) {
            log.warn("Scapestack browser connection request failed", ex);
            return false;
        }
    }

    static String normalizeCode(String code) {
        if (code == null) return "";
        String clean = code.toUpperCase().replaceAll("[^A-Z0-9]", "");
        return clean.substring(0, Math.min(8, clean.length()));
    }

    static String pairingUrlFromSyncUrl(String syncUrl) {
        String clean = ClaimClient.normalizeSyncUrl(syncUrl);
        if (clean.endsWith("/api/sync")) {
            return clean.substring(0, clean.length() - "/api/sync".length()) + "/api/account/pair/approve";
        }
        if (clean.endsWith("/sync")) {
            return clean.substring(0, clean.length() - "/sync".length()) + "/account/pair/approve";
        }
        return clean + "/account/pair/approve";
    }
}

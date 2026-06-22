package app.scapestack.runelite;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.function.Consumer;

@Slf4j
final class SyncServiceReadiness {

    static final class Result {
        final boolean proceed;
        final String message;

        private Result(boolean proceed, String message) {
            this.proceed = proceed;
            this.message = message;
        }

        static Result proceed(String message) {
            return new Result(true, message);
        }

        static Result stop(String message) {
            return new Result(false, message);
        }
    }

    private final OkHttpClient http;

    SyncServiceReadiness(OkHttpClient http) {
        this.http = http;
    }

    Result check(String syncUrl, String userAgent) {
        return check(syncUrl, userAgent, null);
    }

    Result check(String syncUrl, String userAgent, Consumer<Call> activeCall) {
        Request request;
        try {
            request = new Request.Builder()
                .url(syncUrl)
                .get()
                .header("User-Agent", userAgent)
                .build();
        } catch (IllegalArgumentException ex) {
            return Result.stop("invalid Sync URL");
        }

        Call call = http.newCall(request);
        updateActiveCall(activeCall, call);
        try (Response response = call.execute()) {
            String body = ServerResponseSummary.readBody(response);
            if (!response.isSuccessful()) {
                log.debug("Scapestack readiness check returned {}", response.code());
                return Result.proceed("readiness check skipped");
            }
            return parse(body);
        } catch (IOException ex) {
            log.debug("Scapestack readiness check failed", ex);
            return Result.proceed("readiness check unavailable");
        } finally {
            updateActiveCall(activeCall, null);
        }
    }

    private static void updateActiveCall(Consumer<Call> activeCall, Call call) {
        if (activeCall != null) {
            activeCall.accept(call);
        }
    }

    static Result parse(String body) {
        if (body == null || body.isBlank()) return Result.proceed("readiness check empty");
        try {
            JsonElement parsed = new JsonParser().parse(body);
            if (!parsed.isJsonObject()) return Result.proceed("readiness check ignored");
            JsonObject object = parsed.getAsJsonObject();
            JsonElement ready = object.get("ready");
            if (ready == null || !ready.isJsonPrimitive() || !ready.getAsJsonPrimitive().isBoolean()) {
                return Result.proceed("readiness check ignored");
            }
            if (ready.getAsBoolean()) return Result.proceed("service ready");
            return Result.stop(readinessFailureMessage(object));
        } catch (RuntimeException ex) {
            return Result.proceed("readiness check ignored");
        }
    }

    private static String readinessFailureMessage(JsonObject object) {
        JsonObject database = object.has("database") && object.get("database").isJsonObject()
            ? object.getAsJsonObject("database")
            : null;
        if (database == null) return "sync service is not ready";

        JsonElement configured = database.get("configured");
        if (configured != null && configured.isJsonPrimitive() && configured.getAsJsonPrimitive().isBoolean()
            && !configured.getAsBoolean()) {
            return "sync database is not configured";
        }

        JsonElement reason = database.get("reason");
        if (reason != null && reason.isJsonPrimitive()) {
            String text = reason.getAsString().trim();
            if (!text.isEmpty()) return text;
        }

        return "sync database schema is incomplete";
    }
}

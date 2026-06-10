package app.scapestack.runelite;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;

final class ServerResponseSummary {

    private ServerResponseSummary() {}

    static String readBody(Response response) {
        ResponseBody body = response.body();
        if (body == null) return "";
        try {
            return body.string();
        } catch (IOException ex) {
            return "";
        }
    }

    static String failureDetail(int statusCode, String body) {
        String error = errorFromBody(body);
        if (!error.isEmpty()) return limit(error, 140);
        return "HTTP " + statusCode;
    }

    static String logDetail(int statusCode, String body) {
        String error = errorFromBody(body);
        if (!error.isEmpty()) return "HTTP " + statusCode + ": " + limit(error, 240);
        String compact = limit(body, 240);
        return compact.isEmpty() ? "HTTP " + statusCode : "HTTP " + statusCode + ": " + compact;
    }

    static String errorFromBody(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonElement parsed = new JsonParser().parse(body);
            if (!parsed.isJsonObject()) return "";
            JsonObject object = parsed.getAsJsonObject();
            JsonElement error = object.get("error");
            if (error == null || !error.isJsonPrimitive()) return "";
            return error.getAsString().trim();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static String limit(String value, int max) {
        if (value == null) return "";
        String compact = value.replaceAll("\\s+", " ").trim();
        if (compact.length() <= max) return compact;
        return compact.substring(0, Math.max(0, max - 1)) + "…";
    }
}

package app.scapestack.runelite;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;

final class ServerResponseSummary {

    private ServerResponseSummary() {}

    static final class AcceptedCounts {
        final Integer skills;
        final Integer quests;
        final Integer diaries;
        final Integer collectionLogItems;
        final Integer bankItems;

        AcceptedCounts(Integer skills, Integer quests, Integer diaries, Integer collectionLogItems, Integer bankItems) {
            this.skills = skills;
            this.quests = quests;
            this.diaries = diaries;
            this.collectionLogItems = collectionLogItems;
            this.bankItems = bankItems;
        }
    }

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

    static AcceptedCounts acceptedCounts(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonElement parsed = new JsonParser().parse(body);
            if (!parsed.isJsonObject()) return null;
            JsonObject root = parsed.getAsJsonObject();
            JsonElement countsElement = root.get("counts");
            if (countsElement == null || !countsElement.isJsonObject()) return null;
            JsonObject counts = countsElement.getAsJsonObject();
            return new AcceptedCounts(
                integerField(counts, "skills"),
                integerField(counts, "quests"),
                integerField(counts, "diaries"),
                integerField(counts, "collectionLogItems"),
                integerField(counts, "bankItems")
            );
        } catch (RuntimeException ex) {
            return null;
        }
    }

    static boolean hasNewProgress(String body) {
        if (body == null || body.isBlank()) return false;
        try {
            JsonElement parsed = new JsonParser().parse(body);
            if (!parsed.isJsonObject()) return false;
            JsonObject root = parsed.getAsJsonObject();
            JsonElement summaryElement = root.get("syncSummary");
            if (summaryElement == null || !summaryElement.isJsonObject()) return false;
            JsonObject summary = summaryElement.getAsJsonObject();
            return arrayHasItems(summary, "questsCompleted")
                || arrayHasItems(summary, "diariesCompleted")
                || arrayHasItems(summary, "collectionLogItemIds");
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean arrayHasItems(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() && element.getAsJsonArray().size() > 0;
    }

    private static Integer integerField(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) return null;
        try {
            Number value = element.getAsNumber();
            return Math.max(0, value.intValue());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String limit(String value, int max) {
        if (value == null) return "";
        String compact = value.replaceAll("\\s+", " ").trim();
        if (compact.length() <= max) return compact;
        return compact.substring(0, Math.max(0, max - 1)) + "…";
    }
}

package app.scapestack.runelite;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Volledige end-to-end smoketest: voert de exacte HTTP-flow uit die de
 * RuneLite plugin doet, tegen de live Next.js dev-server op
 * http://localhost:4173. De server praat met de echte Neon DB.
 *
 * Voorwaarden:
 *   - dev-server draait (npm run dev)
 *   - DATABASE_URL gezet in .env.local
 *   - Hiscores bereikbaar (gebruikt 'Lynx Titan' — bestaat altijd)
 *
 * Wordt overgeslagen als de server niet bereikbaar is. Run via:
 *   JAVA_HOME=~/jdk-11/jdk-11.0.31+11/Contents/Home ./gradlew test
 *
 * De assertions verifieren de volledige threat model:
 *   1. Eerste claim slaagt (200)
 *   2. Sync zonder token wordt geweigerd (401)
 *   3. Sync met andere token wordt geweigerd (403)
 *   4. Sync met juiste token slaagt + counts kloppen
 *   5. Rival-install kan dezelfde RSN niet stelen (409)
 *   6. Idempotent re-claim met zelfde token werkt
 *   7. InstallToken in-memory store gedraagt zich identiek aan
 *      ConfigManager-pad (cache hit, cache miss, claim memo)
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class EndToEndSmokeTest {

    private static final String BASE = "http://localhost:4173";
    private static final String RSN = "Lynx Titan";
    // Unieke test-token zodat we niet botsen met live data uit eerdere runs.
    // Format moet voldoen aan het serverside regex: [A-Za-z0-9-_.~]{16,200}
    private static final String TEST_TOKEN  = "e2e-test-token-aaaaaaaaaaaaaaaaaaaaa";
    private static final String OTHER_TOKEN = "e2e-other-token-bbbbbbbbbbbbbbbbbbbbb";

    private static final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build();
    private static final MediaType JSON = MediaType.parse("application/json");

    @BeforeClass
    public static void requireServer() throws Exception {
        assumeTrue("Skipped: dev-server niet bereikbaar op " + BASE + " — start hem met `npm run dev`",
            serverReachable());
        // Maak deterministisch: drop bestaande claim-rij voor deze RSN
        // zodat testA altijd "eerste claim" is en testE altijd vers
        // 409 oplevert. Skipt zonder fout als DATABASE_URL niet beschikbaar
        // is — dev kan dan handmatig opruimen.
        String dbUrl = System.getProperty("DATABASE_URL");
        assumeTrue("Skipped: DATABASE_URL niet doorgegeven aan JVM — voeg toe via -DDATABASE_URL=... "
            + "of zet hem in .env.local en draai via gradle", dbUrl != null && !dbUrl.isBlank());
        try (Connection c = DriverManager.getConnection(toJdbcUrl(dbUrl), jdbcProps(dbUrl))) {
            try (PreparedStatement p = c.prepareStatement("DELETE FROM player_claim WHERE rsn=?")) {
                p.setString(1, RSN.toLowerCase());
                p.executeUpdate();
            }
        }
    }

    /** Converteert Neon's postgresql://user:pass@host/db?sslmode=...
     *  naar JDBC's jdbc:postgresql://host/db?sslmode=... + properties. */
    static String toJdbcUrl(String connStr) {
        URI uri = URI.create(connStr);
        StringBuilder sb = new StringBuilder("jdbc:postgresql://");
        sb.append(uri.getHost());
        if (uri.getPort() != -1) sb.append(':').append(uri.getPort());
        sb.append(uri.getPath());
        if (uri.getQuery() != null) {
            // Filter alleen JDBC-bekende params door; channel_binding is
            // pg-libpq-specifiek en wordt niet door de JDBC driver herkend.
            StringBuilder qs = new StringBuilder();
            for (String pair : uri.getQuery().split("&")) {
                if (pair.startsWith("sslmode=")) {
                    if (qs.length() > 0) qs.append('&');
                    qs.append(pair);
                }
            }
            if (qs.length() > 0) sb.append('?').append(qs);
        }
        return sb.toString();
    }

    static Properties jdbcProps(String connStr) {
        URI uri = URI.create(connStr);
        Properties props = new Properties();
        if (uri.getUserInfo() != null) {
            String[] up = uri.getUserInfo().split(":", 2);
            props.setProperty("user", up[0]);
            if (up.length > 1) props.setProperty("password", up[1]);
        }
        return props;
    }

    @AfterClass
    public static void cleanup() {
        // Niet nodig — de claim blijft staan; volgende runs zijn ook ok.
    }

    // ---------- 1) eerste claim ----------

    @Test
    public void testA_firstClaimSucceeds() throws IOException {
        Result r = postJson(BASE + "/api/sync/claim",
            "{\"rsn\":\"" + RSN + "\"}",
            "Bearer " + TEST_TOKEN);
        // BeforeClass heeft de claim opgeruimd, dus dit is altijd eerste keer.
        assertEquals("Eerste claim moet 200 zijn. body=" + r.body, 200, r.status);
        assertTrue("body moet ok:true bevatten", r.body.contains("\"ok\":true"));
    }

    // ---------- 2) sync zonder token wordt geweigerd ----------

    @Test
    public void testB_syncWithoutTokenIs401() throws IOException {
        Result r = postJson(BASE + "/api/sync",
            "{\"rsn\":\"" + RSN + "\",\"questsCompleted\":[],\"diariesCompleted\":[],\"collectionLogItemIds\":[]}",
            null);
        assertEquals("Geen token → 401. body=" + r.body, 401, r.status);
    }

    // ---------- 3) sync met verkeerde token → 403 ----------

    @Test
    public void testC_syncWithWrongTokenIs403() throws IOException {
        Result r = postJson(BASE + "/api/sync",
            "{\"rsn\":\"" + RSN + "\",\"questsCompleted\":[],\"diariesCompleted\":[],\"collectionLogItemIds\":[]}",
            "Bearer " + OTHER_TOKEN);
        assertEquals("Verkeerde token → 403. body=" + r.body, 403, r.status);
    }

    // ---------- 4) volledige sync via plugin-pad ----------

    @Test
    public void testD_fullSyncRoundTrip() throws IOException {
        // Simuleer de body die ScapestackSyncPlugin.triggerSync() bouwt:
        // 3 quests, 2 diaries, 3 CL-items.
        JsonObject body = new JsonObject();
        body.addProperty("rsn", RSN);
        body.addProperty("displayName", RSN);
        body.addProperty("pluginVersion", "junit-e2e");
        JsonArray quests = new JsonArray();
        quests.add("Cooks Assistant");
        quests.add("Dragon Slayer I");
        quests.add("Recipe for Disaster");
        body.add("questsCompleted", quests);
        JsonArray diaries = new JsonArray();
        JsonObject d1 = new JsonObject();
        d1.addProperty("region", "Karamja"); d1.addProperty("tier", "Easy");
        diaries.add(d1);
        JsonObject d2 = new JsonObject();
        d2.addProperty("region", "Falador"); d2.addProperty("tier", "Hard");
        diaries.add(d2);
        body.add("diariesCompleted", diaries);
        JsonArray cl = new JsonArray();
        cl.add(11785); cl.add(11787); cl.add(12922);
        body.add("collectionLogItemIds", cl);

        Result r = postJson(BASE + "/api/sync", body.toString(),
            "Bearer " + TEST_TOKEN);
        assertEquals("Volledige sync moet 200 zijn. body=" + r.body, 200, r.status);
        // Counts in response moeten gelijk zijn aan wat we postten.
        assertTrue("counts.quests:3 verwacht: " + r.body, r.body.contains("\"quests\":3"));
        assertTrue("counts.diaries:2 verwacht: " + r.body, r.body.contains("\"diaries\":2"));
        assertTrue("counts.collectionLogItems:3 verwacht: " + r.body,
            r.body.contains("\"collectionLogItems\":3"));
    }

    // ---------- 5) rival-install kan RSN niet stelen ----------

    @Test
    public void testE_rivalClaimIs409() throws IOException {
        Result r = postJson(BASE + "/api/sync/claim",
            "{\"rsn\":\"" + RSN + "\"}",
            "Bearer " + OTHER_TOKEN);
        assertEquals("Rival token op zelfde RSN → 409. body=" + r.body, 409, r.status);
    }

    // ---------- 6) idempotent re-claim ----------

    @Test
    public void testF_sameTokenReclaim() throws IOException {
        Result r = postJson(BASE + "/api/sync/claim",
            "{\"rsn\":\"" + RSN + "\"}",
            "Bearer " + TEST_TOKEN);
        assertEquals("Re-claim met zelfde token → 200. body=" + r.body, 200, r.status);
    }

    // ---------- 7) InstallToken gedrag (de plugin-side helper) ----------

    @Test
    public void testG_installTokenCacheHit() {
        Map<String, String> store = new HashMap<>();
        store.put("installToken", "preexisting-token-vanuit-runelite-config");
        InstallToken.KeyValueStore kv = mapStore(store);

        String got = InstallToken.getOrCreate(kv);
        assertEquals("Bestaande token moet teruggegeven worden, niet overschreven",
            "preexisting-token-vanuit-runelite-config", got);
    }

    @Test
    public void testH_installTokenCacheMissGeneratesUuid() {
        Map<String, String> store = new HashMap<>();
        InstallToken.KeyValueStore kv = mapStore(store);

        String got = InstallToken.getOrCreate(kv);
        assertNotNull(got);
        assertTrue("Nieuwe token moet UUID-vorm hebben: " + got,
            got.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        assertEquals("Token moet naar store geschreven zijn", got, store.get("installToken"));
    }

    @Test
    public void testI_claimedRsnMemo() {
        Map<String, String> store = new HashMap<>();
        InstallToken.KeyValueStore kv = mapStore(store);
        assertNull("Vers, niets onthouden", InstallToken.claimedRsn(kv));
        InstallToken.rememberClaimedRsn(kv, "Lynx Titan");
        assertEquals("Lynx Titan", InstallToken.claimedRsn(kv));
    }

    @Test
    public void testJ_forgetClaimKeepsInstallToken() {
        Map<String, String> store = new HashMap<>();
        InstallToken.KeyValueStore kv = mapStore(store);
        String token = InstallToken.getOrCreate(kv);
        InstallToken.rememberClaimedRsn(kv, "Lynx Titan");

        InstallToken.forgetClaim(kv);

        assertNull("Claim-cache is cleared", InstallToken.claimedRsn(kv));
        assertEquals("Install token remains stable", token, InstallToken.getOrCreate(kv));
    }

    // ---------- helpers ----------

    private static InstallToken.KeyValueStore mapStore(Map<String, String> backing) {
        return new InstallToken.KeyValueStore() {
            @Override public String get(String key) { return backing.get(key); }
            @Override public void set(String key, String value) { backing.put(key, value); }
        };
    }

    private static boolean serverReachable() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(BASE).openConnection();
            c.setConnectTimeout(1000);
            c.setReadTimeout(1000);
            c.setRequestMethod("HEAD");
            int code = c.getResponseCode();
            return code < 500;
        } catch (IOException e) {
            return false;
        }
    }

    private static final class Result {
        final int status;
        final String body;
        Result(int s, String b) { this.status = s; this.body = b; }
    }

    private static Result postJson(String url, String json, String authHeader) throws IOException {
        Request.Builder b = new Request.Builder()
            .url(url)
            .post(RequestBody.create(JSON, json))
            .header("User-Agent", "scapestack-junit-e2e");
        if (authHeader != null) b.header("Authorization", authHeader);
        try (Response res = http.newCall(b.build()).execute()) {
            String body = res.body() != null ? res.body().string() : "";
            return new Result(res.code(), body);
        }
    }
}

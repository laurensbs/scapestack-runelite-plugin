package app.scapestack.runelite;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure-function tests for {@link ClaimClient#claimUrlFromSyncUrl(String)}.
 * The HTTP path is exercised end-to-end in the server's vitest suite
 * (tests/sync-auth.test.ts) — here we just verify the URL derivation
 * stays in lock-step with the server's route layout.
 */
public class ClaimClientTest {

    @Test
    public void claimSendsTokenAsAuthorizationBearerNotJsonBody() {
        final String[] authHeader = new String[1];
        final String[] bodyText = new String[1];
        OkHttpClient http = new OkHttpClient.Builder()
            .addInterceptor(chain -> {
                authHeader[0] = chain.request().header("Authorization");
                Buffer buffer = new Buffer();
                chain.request().body().writeTo(buffer);
                bodyText[0] = buffer.readUtf8();
                return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(MediaType.parse("application/json"), "{\"ok\":true}"))
                    .build();
            })
            .build();

        ClaimClient client = new ClaimClient(http);
        assertTrue(client.claim(
            "https://www.scapestack.org/api/sync/claim",
            "Lynx Titan",
            "11111111-2222-3333-4444-555555555555",
            "scapestack-test"
        ));

        assertEquals("Bearer 11111111-2222-3333-4444-555555555555", authHeader[0]);
        assertEquals("{\"rsn\":\"Lynx Titan\"}", bodyText[0]);
        assertFalse(bodyText[0].contains("token"));
    }

    @Test
    public void derivesClaimFromDefaultSyncUrl() {
        assertEquals(
            "https://www.scapestack.org/api/sync/claim",
            ClaimClient.claimUrlFromSyncUrl("https://www.scapestack.org/api/sync")
        );
    }

    @Test
    public void handlesTrailingSlashOnSync() {
        assertEquals(
            "https://www.scapestack.org/api/sync/claim",
            ClaimClient.claimUrlFromSyncUrl("https://www.scapestack.org/api/sync/")
        );
    }

    @Test
    public void trimsWhitespaceBeforeDerivingClaimUrl() {
        assertEquals(
            "http://127.0.0.1:4173/api/sync/claim",
            ClaimClient.claimUrlFromSyncUrl("  http://127.0.0.1:4173/api/sync  ")
        );
    }

    @Test
    public void stripsQueryAndFragmentBeforeDerivingClaimUrl() {
        assertEquals(
            "https://example.com/api/sync/claim",
            ClaimClient.claimUrlFromSyncUrl("https://example.com/api/sync?debug=1#claim")
        );
    }

    @Test
    public void recoversWhenClaimUrlWasPastedAsSyncUrl() {
        assertEquals(
            "https://www.scapestack.org/api/sync/claim",
            ClaimClient.claimUrlFromSyncUrl("https://www.scapestack.org/api/sync/claim")
        );
    }

    @Test
    public void appendsClaimForLocalhostDev() {
        assertEquals(
            "http://localhost:4173/api/sync/claim",
            ClaimClient.claimUrlFromSyncUrl("http://localhost:4173/api/sync")
        );
    }

    @Test
    public void fallbackAppendsForUnexpectedShape() {
        // A self-hosting user might point at /api directly. We don't
        // promise this works server-side, but we shouldn't drop the path.
        assertEquals(
            "https://example.com/api/claim",
            ClaimClient.claimUrlFromSyncUrl("https://example.com/api")
        );
    }

    @Test
    public void emptyStringFallback() {
        assertEquals("", ClaimClient.claimUrlFromSyncUrl(null));
    }

    @Test
    public void normalizeSyncUrlKeepsPlainEndpoint() {
        assertEquals(
            "https://www.scapestack.org/api/sync",
            ClaimClient.normalizeSyncUrl("https://www.scapestack.org/api/sync/")
        );
    }

    @Test
    public void normalizeSyncUrlConvertsPastedClaimEndpoint() {
        assertEquals(
            "https://www.scapestack.org/api/sync",
            ClaimClient.normalizeSyncUrl(" https://www.scapestack.org/api/sync/claim/?debug=1 ")
        );
    }

    @Test
    public void validatesOnlyHttpSyncEndpoints() {
        assertTrue(ClaimClient.isHttpSyncUrl("https://www.scapestack.org/api/sync"));
        assertTrue(ClaimClient.isHttpSyncUrl(" http://127.0.0.1:4173/api/sync/claim?debug=1 "));
        assertFalse(ClaimClient.isHttpSyncUrl("ftp://example.com/api/sync"));
        assertFalse(ClaimClient.isHttpSyncUrl("file:///tmp/scapestack.json"));
        assertFalse(ClaimClient.isHttpSyncUrl("https://"));
        assertFalse(ClaimClient.isHttpSyncUrl(null));
    }
}

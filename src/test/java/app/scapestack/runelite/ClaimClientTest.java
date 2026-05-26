package app.scapestack.runelite;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pure-function tests for {@link ClaimClient#claimUrlFromSyncUrl(String)}.
 * The HTTP path is exercised end-to-end in the server's vitest suite
 * (tests/sync-auth.test.ts) — here we just verify the URL derivation
 * stays in lock-step with the server's route layout.
 */
public class ClaimClientTest {

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
}

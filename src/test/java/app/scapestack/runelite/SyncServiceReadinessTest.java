package app.scapestack.runelite;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class SyncServiceReadinessTest {

    @Test
    public void proceedsWhenServiceReportsReady() {
        SyncServiceReadiness.Result result = SyncServiceReadiness.parse(
            "{\"ready\":true,\"plugin\":{\"currentVersion\":\"0.2.0\"}}"
        );

        assertTrue(result.proceed);
        assertEquals("service ready", result.message);
    }

    @Test
    public void stopsWhenDatabaseIsMissing() {
        SyncServiceReadiness.Result result = SyncServiceReadiness.parse(
            "{\"ready\":false,\"database\":{\"configured\":false,\"reason\":\"DATABASE_URL is not set\"}}"
        );

        assertFalse(result.proceed);
        assertEquals("sync database is not configured", result.message);
    }

    @Test
    public void stopsWhenSchemaIsIncomplete() {
        SyncServiceReadiness.Result result = SyncServiceReadiness.parse(
            "{\"ready\":false,\"database\":{\"configured\":true,\"ready\":false,\"missingColumns\":{\"player_sync\":[\"slayer\"]}}}"
        );

        assertFalse(result.proceed);
        assertEquals("sync database schema is incomplete", result.message);
    }

    @Test
    public void proceedsWhenReadinessBodyIsUnknown() {
        SyncServiceReadiness.Result result = SyncServiceReadiness.parse("{\"ok\":true}");

        assertTrue(result.proceed);
        assertEquals("readiness check ignored", result.message);
    }
}

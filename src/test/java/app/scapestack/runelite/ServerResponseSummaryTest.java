package app.scapestack.runelite;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ServerResponseSummaryTest {

    @Test
    public void extractsErrorFromJsonBody() {
        assertEquals(
            "Token does not match RSN claim",
            ServerResponseSummary.errorFromBody("{\"ok\":false,\"error\":\"Token does not match RSN claim\"}")
        );
    }

    @Test
    public void failureDetailPrefersServerError() {
        assertEquals(
            "RSN not found on Hiscores",
            ServerResponseSummary.failureDetail(404, "{\"error\":\"RSN not found on Hiscores\"}")
        );
    }

    @Test
    public void failureDetailFallsBackToHttpStatus() {
        assertEquals(
            "HTTP 500",
            ServerResponseSummary.failureDetail(500, "<html>bad gateway</html>")
        );
    }

    @Test
    public void logDetailIncludesCompactFallbackBody() {
        assertEquals(
            "HTTP 502: upstream unavailable",
            ServerResponseSummary.logDetail(502, " upstream\nunavailable ")
        );
    }
}

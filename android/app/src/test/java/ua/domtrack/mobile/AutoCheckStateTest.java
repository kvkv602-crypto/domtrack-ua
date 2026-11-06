package ua.domtrack.mobile;

import static org.junit.Assert.*;
import org.json.*;
import org.junit.Test;

public class AutoCheckStateTest {
    @Test public void newlyEnabledTargetsDoNotInheritAnEmptyOrDisabledRunDelay() {
        assertTrue(AutoCheckState.due(Json.object("status", "idle", "finishedAtMillis", 1000000L), 1000001L));
        assertTrue(AutoCheckState.due(Json.object("status", "disabled", "finishedAtMillis", 1000000L), 1000001L));
    }
    @Test public void allSourceCooldownPreservesTheEarliestEligibleTime() {
        JSONObject waiting = AutoCheckState.finish(new JSONObject(), "waiting", "source pause",
            Json.object("nextRetryAtMillis", 15400000L), 1000000L);
        assertFalse(AutoCheckState.due(waiting, 4600000L));
        assertTrue(AutoCheckState.due(waiting, 15400000L));
    }
    @Test public void firstCheckIsDueButSuccessfulChecksWaitFifteenMinutes() {
        assertTrue(AutoCheckState.due(new JSONObject(), 1000000L));
        JSONObject completed = Json.object("status", "checked", "finishedAtMillis", 1000000L);
        assertFalse(AutoCheckState.due(completed, 1899999L));
        assertTrue(AutoCheckState.due(completed, 1900000L));
    }

    @Test public void anInterruptedProcessDoesNotLeaveAnEternalRunningFlag() {
        JSONObject running = Json.object("status", "running", "startedAtMillis", 2000000L);
        assertFalse(AutoCheckState.due(running, 2030000L));
        assertTrue(AutoCheckState.due(running, 2120001L));
    }

    @Test public void aBusyAttemptCanRetryWithoutWaitingAnotherWholePeriod() {
        JSONObject deferred = Json.object("status", "deferred", "finishedAtMillis", 2000000L,
            "retryAfterMillis", 2030000L);
        assertFalse(AutoCheckState.due(deferred, 2029999L));
        assertTrue(AutoCheckState.due(deferred, 2030000L));
    }

    @Test public void failedRequestsAreNotReportedAsCheckedPages() {
        JSONObject failed = Json.object("pages", 4, "loadedPages", 0, "watchChecks", 0,
            "errors", new JSONArray().put("HTTP 403"));
        assertEquals("blocked", AutoCheckState.outcome(failed));
        assertEquals("partial", AutoCheckState.outcome(Json.object("loadedPages", 1,
            "errors", new JSONArray().put("another host unavailable"))));
        assertEquals("checked", AutoCheckState.outcome(Json.object("loadedPages", 2)));
        assertEquals("checked", AutoCheckState.outcome(Json.object("watchChecks", 1)));
        assertEquals("waiting", AutoCheckState.outcome(Json.object("pages", 0)));
    }

    @Test public void cancellationRetainsLastSuccessfulCheckAndDoesNotClaimCompletion() {
        JSONObject started = AutoCheckState.begin(Json.object("attempts", 2,
            "lastSuccessAtMillis", 1000000L), 2000000L, "periodic");
        assertEquals(3, started.optInt("attempts"));
        JSONObject scan = Json.object("stopped", true, "pages", 1, "loadedPages", 1);
        assertEquals("deferred", AutoCheckState.outcome(scan));
        JSONObject finished = AutoCheckState.finish(started, "deferred", "busy", scan, 2001000L);
        assertEquals(1000000L, finished.optLong("lastSuccessAtMillis"));
        assertEquals(2031000L, finished.optLong("retryAfterMillis"));
    }

    @Test public void statusKeepsRealWorkCountsEvenWhenNoNewListingsWereFound() {
        JSONObject started = AutoCheckState.begin(new JSONObject(), 2000000L, "catch-up");
        JSONObject finished = AutoCheckState.finish(started, "checked", "", Json.object(
            "pages", 4, "loadedPages", 4, "watchChecks", 1, "imported", 0), 2005000L);
        assertEquals(4, finished.optInt("loadedPages"));
        assertEquals(1, finished.optInt("watchChecks"));
        assertEquals(0, finished.optInt("imported"));
        assertEquals(2005000L, finished.optLong("lastSuccessAtMillis"));
        assertFalse(AutoCheckState.due(finished, 2035000L));
    }
}

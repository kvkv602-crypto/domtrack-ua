package ua.domtrack.mobile;

import java.time.Instant;
import org.json.*;

/** Persisted automatic-run state; manual scans never advance this clock. */
final class AutoCheckState {
    static final long INTERVAL = 900000L;
    static final long STALE_RUNNING = 120000L;

    static boolean due(JSONObject state, long now) {
        if (state.optString("status").equals("idle") || state.optString("status").equals("disabled")) return true;
        long started = state.optLong("startedAtMillis");
        if ("running".equals(state.optString("status")) && started > 0)
            return now < started || now - started >= STALE_RUNNING;
        long retry = state.optLong("retryAfterMillis");
        if (retry > 0 && retry <= now + 21600000L) return now >= retry;
        long finished = state.optLong("finishedAtMillis");
        return finished <= 0 || now < finished || now - finished >= INTERVAL;
    }

    static JSONObject begin(JSONObject previous, long now, String trigger) {
        JSONObject state = Json.copy(previous);
        Json.put(state, "status", "running");
        Json.put(state, "reason", "");
        Json.put(state, "trigger", trigger);
        Json.put(state, "attempts", previous.optInt("attempts") + 1);
        Json.put(state, "startedAtMillis", now);
        Json.put(state, "startedAt", Instant.ofEpochMilli(now).toString());
        Json.put(state, "retryAfterMillis", 0);
        return state;
    }

    static String outcome(JSONObject report) {
        if (report.optBoolean("stopped")) return "deferred";
        boolean checked = report.optInt("loadedPages") > 0 || report.optInt("watchChecks") > 0;
        JSONArray errors = report.optJSONArray("errors");
        boolean failed = errors != null && errors.length() > 0;
        return checked ? (failed ? "partial" : "checked") : (failed ? "blocked" : "waiting");
    }

    static JSONObject finish(JSONObject previous, String status, String reason,
            JSONObject report, long now) {
        JSONObject state = Json.copy(previous);
        Json.put(state, "status", status);
        Json.put(state, "reason", reason);
        Json.put(state, "finishedAtMillis", now);
        Json.put(state, "finishedAt", Instant.ofEpochMilli(now).toString());
        for (String key : new String[]{"pages", "loadedPages", "watchChecks", "imported"})
            Json.put(state, key, report.optInt(key));
        Json.put(state, "errors", report.optJSONArray("errors") == null
            ? new JSONArray() : report.optJSONArray("errors"));
        Json.put(state, "retryAfterMillis", (status.equals("deferred") || status.equals("failed"))
            ? now + 30000L : status.equals("waiting") ? report.optLong("nextRetryAtMillis") : 0);
        if (status.equals("checked") || status.equals("partial")) {
            Json.put(state, "lastSuccessAtMillis", now);
            Json.put(state, "lastSuccessAt", Instant.ofEpochMilli(now).toString());
        }
        return state;
    }
}

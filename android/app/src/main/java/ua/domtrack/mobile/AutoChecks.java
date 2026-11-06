package ua.domtrack.mobile;

import android.content.Context;
import androidx.work.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.*;

/** Periodic and catch-up work share ScanWorker's automatic-run gate and due clock. */
final class AutoChecks {
    static final String PERIODIC = "domtrack-periodic", CATCHUP = "domtrack-catchup";

    static boolean hasTargets(Database db) {
        return !Scanner.sectionsFor(db.searches(), null).isEmpty() || db.listings().stream()
            .anyMatch(r -> r.optJSONObject("watch") != null
                && r.optJSONObject("watch").optBoolean("enabled", true));
    }

    static Constraints constraints(JSONObject settings) {
        return new Constraints.Builder()
            .setRequiredNetworkType(settings.optBoolean("wifiOnly", false)
                ? NetworkType.UNMETERED : NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build();
    }

    static void schedule(Context context, Database db) {
        WorkManager manager = WorkManager.getInstance(context);
        JSONObject settings = db.state("settings", new JSONObject());
        JSONObject oldPolicy = db.state("autoSchedulePolicy", new JSONObject());
        boolean networkChanged = oldPolicy.has("wifiOnly") && oldPolicy.optBoolean("wifiOnly")
            != settings.optBoolean("wifiOnly", false);
        db.saveState("autoSchedulePolicy", Json.object("wifiOnly", settings.optBoolean("wifiOnly", false)));
        if (!settings.optBoolean("background", true)) {
            manager.cancelUniqueWork(PERIODIC);
            manager.cancelUniqueWork(CATCHUP);
            return;
        }
        PeriodicWorkRequest request = periodicRequest(settings);
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request);
        if (networkChanged) manager.cancelUniqueWork(CATCHUP).getResult().addListener(
            () -> catchUpIfDue(context, db), context.getMainExecutor());
    }

    private static PeriodicWorkRequest periodicRequest(JSONObject settings) {
        return new PeriodicWorkRequest.Builder(
            ScanWorker.class, 15, TimeUnit.MINUTES)
            .setInitialDelay(15, TimeUnit.MINUTES)
            .setInputData(new Data.Builder().putString("trigger", "periodic").build())
            .setConstraints(constraints(settings))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("domtrack-checks").build();
    }

    static void afterCatchUp(Context context, Database db, String trigger) {
        JSONObject settings = db.state("settings", new JSONObject());
        if (!trigger.equals("catch-up") || !settings.optBoolean("background", true)) return;
        // Align the periodic task to this completed catch-up; don't spend retry wakeups on healthy work.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC,
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, periodicRequest(settings));
    }

    static boolean catchUpIfDue(Context context, Database db) {
        JSONObject settings = db.state("settings", new JSONObject());
        if (!settings.optBoolean("background", true) || !hasTargets(db)) return false;
        if (!AutoCheckState.due(db.state("autoCheck", new JSONObject()),
                System.currentTimeMillis())) return false;
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ScanWorker.class)
            .setInputData(new Data.Builder().putString("trigger", "catch-up").build())
            .setConstraints(constraints(settings))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("domtrack-checks").build();
        WorkManager.getInstance(context).enqueueUniqueWork(CATCHUP, ExistingWorkPolicy.KEEP, request);
        return true;
    }

    static void targetsChanged(Context context, Database db) {
        JSONObject state = db.state("autoCheck", new JSONObject());
        if (state.optString("status").equals("waiting")) {
            Json.put(state, "retryAfterMillis", 0);
            Json.put(state, "finishedAtMillis", 0);
            db.saveState("autoCheck", state);
        }
        schedule(context, db);
        catchUpIfDue(context, db);
    }

    static String time(String value) {
        try { return DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")
            .withZone(ZoneId.systemDefault()).format(Instant.parse(value)); }
        catch (Exception ignored) { return "ещё не было"; }
    }

    static String summary(Database db) {
        if (!db.state("settings", new JSONObject()).optBoolean("background", true))
            return "Автопроверка выключена в настройках";
        JSONObject s = db.state("autoCheck", new JSONObject());
        String status = s.optString("status");
        String label = switch (status) {
            case "running" -> "выполняется";
            case "checked" -> "завершена";
            case "partial" -> "выполнена частично";
            case "deferred" -> "отложена, будет повторена";
            case "blocked" -> "сайты недоступны";
            case "failed" -> "ошибка, будет повторена";
            case "idle" -> "нет включённых поисков или наблюдений";
            case "waiting" -> "ожидает повторного доступа к сайтам";
            case "disabled" -> "выключена";
            default -> "ожидает первого запуска";
        };
        String result = "Автопроверка: " + label;
        if (s.has("startedAt")) result += " · " + time(s.optString(
            status.equals("running") ? "startedAt" : "finishedAt"));
        if (status.equals("checked") || status.equals("partial")) result +=
            "\nУспешно: страниц " + s.optInt("loadedPages") + ", наблюдений "
            + s.optInt("watchChecks") + "; обработано записей " + s.optInt("imported");
        if (!s.optString("reason").isBlank()) result += "\n" + s.optString("reason");
        return result;
    }
}

package ua.domtrack.mobile;

import android.app.ActivityManager;
import android.content.*;
import android.net.*;
import android.os.*;
import androidx.work.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.*;

final class AutoCheckDiagnostics {
    /** Reads system and WorkManager state on the Activity's IO executor. */
    static String read(Context context, Database db) throws Exception {
        JSONObject settings = db.state("settings", new JSONObject());
        StringBuilder text = new StringBuilder(AutoChecks.summary(db));
        long targets = db.searches().stream().filter(s -> s.optBoolean("enabled", true)).count();
        long watches = db.listings().stream().filter(r -> r.optJSONObject("watch") != null
            && r.optJSONObject("watch").optBoolean("enabled", true)).count();
        text.append("\n\nВключено поисков: ").append(targets).append("; наблюдений: ").append(watches);
        text.append("\nИнтервал: 15 минут; Android может отложить запуск.");
        ConnectivityManager network = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = network.getNetworkCapabilities(network.getActiveNetwork());
        text.append("\nИнтернет: ").append(caps != null && caps.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_INTERNET) ? "сеть доступна" : "нет сети");
        if (settings.optBoolean("wifiOnly", false) && network.isActiveNetworkMetered())
            text.append("\nОжидание Wi-Fi / безлимитной сети включено.");
        PowerManager power = context.getSystemService(PowerManager.class);
        ActivityManager activities = context.getSystemService(ActivityManager.class);
        text.append("\nЭнергосбережение: ").append(power.isPowerSaveMode() ? "включено" : "выключено");
        text.append("\nОграничение фона Android: ").append(
            activities.isBackgroundRestricted() ? "включено" : "не обнаружено");
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null && battery.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false))
            text.append("\nНизкий заряд: задача ждёт восстановления заряда.");
        WorkManager manager = WorkManager.getInstance(context);
        boolean scheduled = false;
        for (String name : List.of(AutoChecks.PERIODIC, AutoChecks.CATCHUP)) {
            List<WorkInfo> jobs = manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS);
            for (WorkInfo job : jobs) {
                if (job.getState().isFinished()) continue;
                scheduled = true;
                text.append("\n\n").append(name.equals(AutoChecks.PERIODIC)
                    ? "Периодическая задача: " : "Догоняющая задача: ");
                text.append(switch (job.getState()) {
                    case RUNNING -> "выполняется";
                    case BLOCKED -> "ожидает зависимостей";
                    default -> "в очереди / ожидает условий";
                });
                long next = job.getNextScheduleTimeMillis();
                if (next > 0 && next < Long.MAX_VALUE) text.append("\nМожет быть запущена не раньше: ")
                    .append(AutoChecks.time(Instant.ofEpochMilli(next).toString()));
                text.append("\nПовторных попыток: ").append(job.getRunAttemptCount());
                if (job.getStopReason() != WorkInfo.STOP_REASON_NOT_STOPPED)
                    text.append("\nПричина последней остановки Android: ").append(job.getStopReason());
            }
        }
        if (!scheduled) text.append("\n\nАктивных задач в планировщике пока нет.");
        JSONObject state = db.state("autoCheck", new JSONObject());
        JSONArray errors = state.optJSONArray("errors");
        if (errors != null) for (int i = 0; i < Math.min(errors.length(), 5); i++)
            text.append("\n").append(errors.optString(i));
        long retry = state.optLong("retryAfterMillis");
        if (retry > System.currentTimeMillis()) text.append("\nПовторная попытка не раньше: ")
            .append(AutoChecks.time(Instant.ofEpochMilli(retry).toString()));
        text.append("\n\nПринудительная остановка приложения блокирует задачи до следующего открытия. "
            + "В настройках Samsung проверьте, не добавлен ли DomTrack в спящие приложения.");
        return text.toString();
    }
}

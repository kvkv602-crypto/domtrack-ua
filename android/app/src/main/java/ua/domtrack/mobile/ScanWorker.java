package ua.domtrack.mobile;

import android.content.Context;
import android.net.ConnectivityManager;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

public class ScanWorker extends Worker {
    private static final AtomicBoolean AUTOMATIC_RUNNING = new AtomicBoolean();
    private volatile Scanner scanner;

    public ScanWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }

    @NonNull
    public Result doWork() {
        Context context = getApplicationContext().getApplicationContext();
        Database db = ((DomTrackApp) context).db();
        if (!AUTOMATIC_RUNNING.compareAndSet(false, true)) return Result.retry();
        JSONObject previous = new JSONObject();
        String trigger = getInputData().getString("trigger");
        if (trigger == null) trigger = "periodic";
        try {
            previous = db.state("autoCheck", new JSONObject());
            JSONObject settings = db.state("settings", new JSONObject());
            if (!settings.optBoolean("background", true)) {
                finish(db, previous, "disabled", "Автоматическая работа выключена", new JSONObject());
                return Result.success();
            }
            if (!AutoChecks.hasTargets(db)) {
                finish(db, previous, "idle", "Сохраните поиск или включите наблюдение за объектом", new JSONObject());
                return Result.success();
            }
            if (!AutoCheckState.due(previous, System.currentTimeMillis()))
                return Result.success();
            previous = AutoCheckState.begin(previous, System.currentTimeMillis(), trigger);
            db.saveState("autoCheck", previous);
            if (Scanner.BUSY.get() || SearchService.RUNNING.get()) {
                finish(db, previous, "deferred", "Сканер занят ручным поиском", new JSONObject());
                return Result.retry();
            }
            if (settings.optBoolean("wifiOnly", false) && context.getSystemService(
                    ConnectivityManager.class).isActiveNetworkMetered()) {
                finish(db, previous, "deferred", "Ожидание Wi-Fi / безлимитной сети", new JSONObject());
                return Result.retry();
            }
            scanner = new Scanner(context, db);
            if (isStopped()) {
                scanner.cancel();
                finish(db, previous, "deferred", "Задача остановлена Android", new JSONObject());
                return Result.retry();
            }
            scanner.scan(false, () -> isStopped() || SearchService.RUNNING.get(), null);
            JSONObject report = scanner.report();
            if (report.length() == 0) {
                finish(db, previous, "deferred", "Сканер занят; проверка будет повторена", report);
                return Result.retry();
            }
            String outcome = AutoCheckState.outcome(report);
            String reason = outcome.equals("deferred") ? "Проверка прервана; очередь сохранена"
                : outcome.equals("waiting") ? "Источники ожидают повторной попытки после ошибки" : "";
            finish(db, previous, outcome, reason, report);
            if (!outcome.equals("deferred")) AutoChecks.afterCatchUp(context, db, trigger);
            return outcome.equals("deferred") ? Result.retry() : Result.success();
        } catch (Exception e) {
            finish(db, previous, "failed", Scanner.safe(e), new JSONObject());
            return Result.retry();
        } finally {
            scanner = null;
            AUTOMATIC_RUNNING.set(false);
        }
    }

    private void finish(Database db, JSONObject previous, String status, String reason, JSONObject report) {
        db.saveState("autoCheck", AutoCheckState.finish(previous, status, reason, report,
            System.currentTimeMillis()));
    }

    public void onStopped() {
        Scanner current = scanner;
        if (current != null) current.cancel();
        super.onStopped();
    }
}

package ua.domtrack.mobile;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;

public class ScanWorker extends Worker {

    public ScanWorker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    @NonNull
    public Result doWork() {
        Database db = ((DomTrackApp) getApplicationContext()).db();
        if (
            !db.state("settings", new org.json.JSONObject()).optBoolean("background", true)
        ) return Result.success();
        try {
            new Scanner(getApplicationContext(), db).scan(false, this::isStopped, null);
            return Result.success();
        } catch (Exception e) {
            db.saveState(
                "lastCheck",
                Json.object(
                    "at",
                    Model.now(),
                    "error",
                    "Не удалось завершить фоновую проверку",
                    "complete",
                    false
                )
            );
            return getRunAttemptCount() < 2 ? Result.retry() : Result.failure();
        }
    }
}

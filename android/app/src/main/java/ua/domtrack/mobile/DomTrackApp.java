package ua.domtrack.mobile;

import android.app.Application;
import androidx.work.*;
import java.util.concurrent.TimeUnit;

public class DomTrackApp extends Application {

    private Database database;

    public void onCreate() {
        super.onCreate();
        database = new Database(this);
        Alerts.channels(this);
        schedule();
    }

    Database db() {
        return database;
    }

    void schedule() {
        org.json.JSONObject settings = database.state("settings", new org.json.JSONObject());
        if (!settings.optBoolean("background", true)) {
            WorkManager.getInstance(this).cancelUniqueWork("domtrack-periodic");
            return;
        }
        Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(
                settings.optBoolean("wifiOnly", false)
                    ? NetworkType.UNMETERED
                    : NetworkType.CONNECTED
            )
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
            ScanWorker.class,
            15,
            TimeUnit.MINUTES,
            5,
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("domtrack-checks")
            .build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "domtrack-periodic",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        );
    }
}

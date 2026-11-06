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
        AutoChecks.schedule(this, database);
    }
}

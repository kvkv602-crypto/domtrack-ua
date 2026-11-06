package ua.domtrack.mobile;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.ListenableWorker;
import androidx.work.WorkManager;
import androidx.work.testing.TestWorkerBuilder;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AutoCheckDeviceTest {
    @Test
    public void busyScannerMustRetryInsteadOfConsumingTheAutomaticCheck() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Database db = ((DomTrackApp) context.getApplicationContext()).db();
        JSONObject settings = db.state("settings", new JSONObject());
        WorkManager.getInstance(context).cancelUniqueWork("domtrack-periodic")
            .getResult().get(15, TimeUnit.SECONDS);
        try {
            db.saveState("settings", Json.object("background", true));
            assertTrue("The test must own the scanner gate", Scanner.BUSY.compareAndSet(false, true));
            ScanWorker worker = TestWorkerBuilder.from(context, ScanWorker.class, Runnable::run).build();
            assertEquals(
                "A busy automatic check must retry after manual work, rather than wait another period",
                ListenableWorker.Result.retry(), worker.doWork()
            );
        } finally {
            Scanner.BUSY.set(false);
            db.saveState("settings", settings);
        }
    }
}

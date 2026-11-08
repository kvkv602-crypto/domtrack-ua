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
import org.junit.FixMethodOrder;
import org.junit.runners.MethodSorters;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class AutoCheckDeviceTest {
    @Test
    public void test01BusyScannerMustRetryInsteadOfConsumingTheAutomaticCheck() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        Database db = ((DomTrackApp) context.getApplicationContext()).db();
        JSONObject settings = db.state("settings", new JSONObject());
        JSONObject searches = db.state("searches", new JSONObject());
        JSONObject automatic = db.state("autoCheck", new JSONObject());
        WorkManager.getInstance(context).cancelUniqueWork("domtrack-periodic")
            .getResult().get(15, TimeUnit.SECONDS);
        WorkManager.getInstance(context).cancelUniqueWork("domtrack-catchup")
            .getResult().get(15, TimeUnit.SECONDS);
        try {
            db.saveState("settings", Json.object("background", true));
            db.saveState("autoCheck", new JSONObject());
            assertTrue("The test must own the scanner gate", Scanner.BUSY.compareAndSet(false, true));
            db.saveSearch(Json.object("name", "Automatic check fixture", "enabled", true,
                "filters", Json.object("city", "Запоріжжя", "kind", "apartment")));
            ScanWorker worker = TestWorkerBuilder.from(context, ScanWorker.class, Runnable::run).build();
            assertEquals(
                "A busy automatic check must retry after manual work, rather than wait another period",
                ListenableWorker.Result.retry(), worker.doWork()
            );
        } finally {
            Scanner.BUSY.set(false);
            db.saveState("settings", settings);
            db.saveState("searches", searches);
            db.saveState("autoCheck", automatic);
        }
    }

    @Test
    public void test02DisabledChecksHaveAnObservableReasonWithoutNetworkWork() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        Database db = ((DomTrackApp) context).db();
        JSONObject settings = db.state("settings", new JSONObject());
        JSONObject automatic = db.state("autoCheck", new JSONObject());
        try {
            db.saveState("settings", Json.object("background", false));
            ScanWorker worker = TestWorkerBuilder.from(context, ScanWorker.class, Runnable::run).build();
            assertEquals(ListenableWorker.Result.success(), worker.doWork());
            JSONObject result = db.state("autoCheck", new JSONObject());
            assertEquals("disabled", result.optString("status"));
            assertEquals(0, result.optInt("loadedPages"));
            assertFalse(result.optString("reason").isBlank());
        } finally {
            db.saveState("settings", settings);
            db.saveState("autoCheck", automatic);
        }
    }

    @Test
    public void test02HealthyDuplicateDoesNotCreateRetryBackoffOrEraseTheLastCheck() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        Database db = ((DomTrackApp) context).db();
        JSONObject settings = db.state("settings", new JSONObject());
        JSONObject searches = db.state("searches", new JSONObject());
        JSONObject automatic = db.state("autoCheck", new JSONObject());
        try {
            db.saveState("settings", Json.object("background", true));
            db.saveState("autoCheck", Json.object("status", "checked", "finishedAtMillis",
                System.currentTimeMillis(), "loadedPages", 4, "attempts", 7));
            db.saveSearch(Json.object("name", "Recent check fixture", "enabled", true,
                "filters", Json.object("city", "Запоріжжя", "kind", "apartment")));
            ScanWorker worker = TestWorkerBuilder.from(context, ScanWorker.class, Runnable::run).build();
            assertEquals(ListenableWorker.Result.success(), worker.doWork());
            JSONObject result = db.state("autoCheck", new JSONObject());
            assertEquals("checked", result.optString("status"));
            assertEquals(7, result.optInt("attempts"));
            assertEquals(4, result.optInt("loadedPages"));
        } finally {
            db.saveState("settings", settings);
            db.saveState("searches", searches);
            db.saveState("autoCheck", automatic);
        }
    }

}

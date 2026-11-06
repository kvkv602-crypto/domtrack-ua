package ua.domtrack.mobile;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.ListenableWorker;
import androidx.work.WorkManager;
import androidx.work.testing.TestWorkerBuilder;
import androidx.test.uiautomator.*;
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

    @Test
    public void test03TwoAutonomousChecksFifteenMinutesApartIncludingScreenOff() throws Exception {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext().getApplicationContext();
        Database db = ((DomTrackApp) context).db();
        JSONObject settings = db.state("settings", new JSONObject());
        JSONObject searches = db.state("searches", new JSONObject());
        JSONObject automatic = db.state("autoCheck", new JSONObject());
        JSONObject filters = db.state("currentFilters", new JSONObject());
        UiDevice device = UiDevice.getInstance(instrumentation);
        WorkManager manager = WorkManager.getInstance(context);
        try {
            manager.cancelUniqueWork(AutoChecks.PERIODIC).getResult().get(15, TimeUnit.SECONDS);
            manager.cancelUniqueWork(AutoChecks.CATCHUP).getResult().get(15, TimeUnit.SECONDS);
            db.saveState("autoCheck", new JSONObject());
            db.saveState("searches", new JSONObject());
            db.saveState("settings", Json.object("background", true, "wifiOnly", false, "localAi", false));
            JSONObject requested = Json.object("city", "Ірпінь", "kind", "apartment", "currency", "EUR");
            db.saveState("currentFilters", requested);
            db.saveSearch(Json.object("name", "Autonomous test", "enabled", true, "filters", requested));
            // No SearchService.start or Search-button action: the Activity's normal activation schedules work.
            device.wakeUp();
            instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            JSONObject first = awaitCheck(db, 0, 180000L);
            long firstAt = first.optLong("lastSuccessAtMillis");
            assertTrue(first.optInt("loadedPages") > 0);
            notice("AUTO_FIRST_CHECK pages=" + first.optInt("loadedPages"));
            device.sleep();
            JSONObject second = awaitCheck(db, firstAt, 1200000L);
            long interval = second.optLong("lastSuccessAtMillis") - firstAt;
            assertTrue("The second automatic scan must respect the 15-minute interval", interval >= 900000L);
            assertTrue(second.optInt("loadedPages") > 0);
            assertEquals("The screen-off repeat must come from the periodic job", "periodic", second.optString("trigger"));
            assertFalse("The screen must stay off until the automatic repeat completes", device.isScreenOn());
            notice("AUTO_SECOND_CHECK intervalMs=" + interval + " pages=" + second.optInt("loadedPages"));
            db.saveState("automaticDeviceTest", Json.object("firstAt", first.optString("lastSuccessAt"),
                "secondAt", second.optString("lastSuccessAt"), "intervalMs", interval,
                "firstLoadedPages", first.optInt("loadedPages"), "secondLoadedPages", second.optInt("loadedPages"),
                "screenOffForSecondRun", true, "manualSearchButtonPressed", false,
                "trigger", second.optString("trigger")));
            device.wakeUp();
            instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            assertTrue("Automatic status must be visible without manual Search", device.wait(
                Until.hasObject(By.textContains("Автопроверка:")), 15000));
            assertTrue(device.takeScreenshot(new java.io.File(context.getFilesDir(), "automatic-check.png")));
        } finally {
            manager.cancelUniqueWork(AutoChecks.PERIODIC).getResult().get(15, TimeUnit.SECONDS);
            manager.cancelUniqueWork(AutoChecks.CATCHUP).getResult().get(15, TimeUnit.SECONDS);
            db.saveState("settings", settings);
            db.saveState("searches", searches);
            db.saveState("autoCheck", automatic);
            db.saveState("currentFilters", filters);
            device.wakeUp();
            instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        }
    }

    private static JSONObject awaitCheck(Database db, long after, long timeout) throws Exception {
        long until = System.currentTimeMillis() + timeout;
        JSONObject state = new JSONObject();
        while (System.currentTimeMillis() < until) {
            state = db.state("autoCheck", new JSONObject());
            if (state.optLong("lastSuccessAtMillis") > after && state.optInt("loadedPages") > 0
                && (state.optString("status").equals("checked") || state.optString("status").equals("partial"))) return state;
            Thread.sleep(2500L);
        }
        fail("No autonomous successful check: " + state);
        return state;
    }

    private static void notice(String text) {
        Bundle status = new Bundle();
        status.putString("stream", "\n" + text + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }
}

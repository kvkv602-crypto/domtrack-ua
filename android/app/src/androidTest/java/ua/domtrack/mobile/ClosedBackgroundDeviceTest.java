package ua.domtrack.mobile;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import androidx.work.*;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Phases driven by ci/closed-background.py; no instrumentation lives during the 15-minute wait. */
@RunWith(AndroidJUnit4.class)
public class ClosedBackgroundDeviceTest {
    private static final String FIXTURE = "closedBackgroundFixture";
    private static final String[] SAVED = {
        "settings", "searches", "autoCheck", "currentFilters", "autoSchedulePolicy"
    };

    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    }

    private static Database database() { return ((DomTrackApp) context()).db(); }

    @Test public void prepareClosedProcessRepeat() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = context();
        Database db = database();
        assertFalse("A previous background fixture must be restored first",
            db.state(FIXTURE, new JSONObject()).optBoolean("active"));
        JSONObject snapshot = Json.object("active", true);
        for (String name : SAVED) Json.put(snapshot, name, db.state(name, new JSONObject()));
        db.saveState(FIXTURE, snapshot);
        boolean prepared = false;
        try {
            cancelChecks(context);
            db.saveState("autoCheck", new JSONObject());
            db.saveState("searches", new JSONObject());
            db.saveState("settings", Json.object("background", true, "wifiOnly", false, "localAi", false));
            JSONObject requested = Json.object("city", "Запоріжжя", "kind", "both", "currency", "EUR");
            db.saveState("currentFilters", requested);
            db.saveSearch(Json.object("id", "unrelated-paused-search", "name", "Unrelated paused search",
                "enabled", false, "filters", Json.object("city", "Київ", "kind", "house")));
            UiDevice device = UiDevice.getInstance(instrumentation);
            device.wakeUp();
            // Ordinary Search must create an observation even when unrelated saved searches exist.
            Activity main = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            java.lang.reflect.Method start = MainActivity.class.getDeclaredMethod("startScan", boolean.class);
            start.setAccessible(true);
            instrumentation.runOnMainSync(() -> {
                try { start.invoke(main, true); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            assertTrue("Ordinary Search must register the latest query alongside paused saved searches",
                db.searches().stream().anyMatch(search -> CurrentSearch.isCurrent(search)
                    && search.optBoolean("enabled") && "Запоріжжя".equals(search.optJSONObject("filters").optString("city"))));
            long serviceDeadline = System.currentTimeMillis() + 15000L;
            while (!db.state("activeSearch", new JSONObject()).optString("filters").contains("Запоріжжя") &&
                    System.currentTimeMillis() < serviceDeadline) Thread.sleep(100L);
            assertTrue("Explicit search must reach the foreground service",
                db.state("activeSearch", new JSONObject()).optString("filters").contains("Запоріжжя"));
            if (SearchService.RUNNING.get()) instrumentation.runOnMainSync(() -> SearchService.stop(context));
            while (SearchService.RUNNING.get() && System.currentTimeMillis() < serviceDeadline) Thread.sleep(100L);
            assertFalse("Manual full search must stop before testing periodic work", SearchService.RUNNING.get());
            JSONObject first = awaitFirstCheck(db, 180000L);
            assertEquals("catch-up", first.optString("trigger"));
            awaitCatchUpFinished(context, 15000L);
            assertTrue("The ordinary 15-minute periodic request must be persisted",
                WorkManager.getInstance(context).getWorkInfosForUniqueWork(AutoChecks.PERIODIC)
                    .get(15, TimeUnit.SECONDS).stream().anyMatch(info ->
                        info.getState() == WorkInfo.State.ENQUEUED));
            Json.put(snapshot, "first", first);
            Json.put(snapshot, "firstProcessId", android.os.Process.myPid());
            Json.put(snapshot, "prepared", true);
            Json.put(snapshot, "currentTargetRegisteredBySearch", true);
            db.saveState(FIXTURE, snapshot);
            instrumentation.runOnMainSync(main::finishAndRemoveTask);
            instrumentation.waitForIdleSync();
            device.pressHome();
            device.sleep();
            assertFalse(device.isScreenOn());
            notice("AUTO_FIRST_CHECK pages=" + first.optInt("loadedPages")
                + " activityClosed=true instrumentationWillExit=true");
            prepared = true;
        } finally {
            if (!prepared) restoreFixture();
        }
    }

    @Test public void verifyClosedProcessRepeatAndRestore() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Database db = database();
        JSONObject snapshot = db.state(FIXTURE, new JSONObject());
        try {
            assertTrue(snapshot.optBoolean("prepared"));
            JSONObject first = snapshot.optJSONObject("first");
            assertNotNull(first);
            JSONObject second = db.state("autoCheck", new JSONObject());
            long interval = second.optLong("lastSuccessAtMillis") - first.optLong("lastSuccessAtMillis");
            assertTrue("Natural periodic repeat must respect the 15-minute interval", interval >= 900000L);
            assertTrue(second.optInt("loadedPages") > 0);
            assertEquals("periodic", second.optString("trigger"));
            assertTrue(second.optString("status").equals("checked") || second.optString("status").equals("partial"));
            UiDevice device = UiDevice.getInstance(instrumentation);
            assertFalse("No Activity was opened during the process-dead wait", device.isScreenOn());
            db.saveState("automaticDeviceTest", Json.object("firstAt", first.optString("lastSuccessAt"),
                "secondAt", second.optString("lastSuccessAt"), "intervalMs", interval,
                "firstLoadedPages", first.optInt("loadedPages"), "secondLoadedPages", second.optInt("loadedPages"),
                "screenOffForSecondRun", true, "manualSearchButtonPressedDuringWait", false,
                "currentTargetRegisteredBySearch", true,
                "externalProcessKillScenario", true, "trigger", second.optString("trigger")));
            notice("AUTO_SECOND_CHECK intervalMs=" + interval + " pages=" + second.optInt("loadedPages")
                + " externalProcessKillScenario=true");
            device.wakeUp();
            instrumentation.startActivitySync(new Intent(context(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            assertTrue(device.wait(Until.hasObject(By.textContains("Автопроверка:")), 15000));
            assertTrue(device.takeScreenshot(new java.io.File(context().getFilesDir(), "automatic-check.png")));
        } finally { restoreFixture(); }
    }

    @Test public void cleanupClosedProcessFixture() throws Exception { restoreFixture(); }

    private static void restoreFixture() throws Exception {
        Context context = context();
        Database db = database();
        JSONObject snapshot = db.state(FIXTURE, new JSONObject());
        if (!snapshot.optBoolean("active")) return;
        cancelChecks(context);
        for (String name : SAVED) {
            JSONObject original = snapshot.optJSONObject(name);
            db.saveState(name, original == null ? new JSONObject() : original);
        }
        db.saveState(FIXTURE, new JSONObject());
        AutoChecks.schedule(context, db);
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).wakeUp();
    }

    private static void cancelChecks(Context context) throws Exception {
        WorkManager manager = WorkManager.getInstance(context);
        manager.cancelUniqueWork(AutoChecks.PERIODIC).getResult().get(15, TimeUnit.SECONDS);
        manager.cancelUniqueWork(AutoChecks.CATCHUP).getResult().get(15, TimeUnit.SECONDS);
    }

    private static JSONObject awaitFirstCheck(Database db, long timeout) throws Exception {
        long until = System.currentTimeMillis() + timeout;
        JSONObject state = new JSONObject();
        while (System.currentTimeMillis() < until) {
            state = db.state("autoCheck", new JSONObject());
            if (state.optLong("lastSuccessAtMillis") > 0 && state.optInt("loadedPages") > 0
                && (state.optString("status").equals("checked") || state.optString("status").equals("partial"))) return state;
            Thread.sleep(2500L);
        }
        fail("No first automatic successful check: " + state);
        return state;
    }

    private static void awaitCatchUpFinished(Context context, long timeout) throws Exception {
        long until = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < until) {
            if (WorkManager.getInstance(context).getWorkInfosForUniqueWork(AutoChecks.CATCHUP)
                .get(15, TimeUnit.SECONDS).stream().noneMatch(info -> info.getState() == WorkInfo.State.RUNNING)) return;
            Thread.sleep(250L);
        }
        fail("The first catch-up must release its process before the external kill");
    }

    private static void notice(String value) {
        Bundle status = new Bundle();
        status.putString("stream", "\n" + value + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }
}

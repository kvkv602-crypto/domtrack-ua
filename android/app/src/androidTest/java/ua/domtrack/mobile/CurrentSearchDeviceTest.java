package ua.domtrack.mobile;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CurrentSearchDeviceTest {
    @Test public void changingCurrentTargetClearsOldWaitingClockWithoutEnablingGlobalBackground() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String fixture = "current-search-backoff-test.db";
        context.deleteDatabase(fixture);
        try (Database db = new Database(context, fixture)) {
            db.saveState("settings", Json.object("background", false));
            db.saveState("autoCheck", Json.object("status", "waiting", "retryAfterMillis",
                System.currentTimeMillis() + 14400000L, "finishedAtMillis", System.currentTimeMillis()));
            db.saveCurrentSearch(Json.object("city", "Ірпінь", "kind", "apartment"));
            assertFalse(AutoCheckState.due(db.state("autoCheck", new JSONObject()), System.currentTimeMillis()));
            AutoChecks.targetsChanged(context, db, false);
            JSONObject state = db.state("autoCheck", new JSONObject());
            assertEquals(0, state.optLong("retryAfterMillis"));
            assertEquals(0, state.optLong("finishedAtMillis"));
            assertTrue(AutoCheckState.due(state, System.currentTimeMillis()));
            assertFalse(db.state("settings", new JSONObject()).optBoolean("background", true));
        } finally { context.deleteDatabase(fixture); }
    }

    @Test public void repeatedOrdinarySearchesPersistOneCurrentTargetAlongsideNamedSavedSearches() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String fixture = "current-search-replacement-test.db";
        context.deleteDatabase(fixture);
        Database db = new Database(context, fixture);
        try {
            JSONObject named = Json.object("id", "named-search", "name", "My saved search", "enabled", false,
                "filters", Json.object("city", "Київ", "kind", "house", "priceMax", 42000));
            db.saveSearch(named);
            db.saveCurrentSearch(Json.object("city", "Одеса", "kind", "house"));
            JSONObject next = db.saveCurrentSearch(Json.object(
                "city", "Запоріжжя", "kind", "apartment", "priceMax", 15000));
            assertEquals("domtrack-current-search", next.optString("id"));
            List<JSONObject> rows = db.searches();
            assertEquals(2, rows.size());
            assertEquals(1, rows.stream().filter(CurrentSearch::isCurrent).count());
            JSONObject current = rows.stream().filter(CurrentSearch::isCurrent).findFirst().orElseThrow();
            assertEquals("Запоріжжя", current.optJSONObject("filters").optString("city"));
            assertEquals(15000, current.optJSONObject("filters").optInt("priceMax"));
            JSONObject retained = rows.stream().filter(s -> "named-search".equals(s.optString("id")))
                .findFirst().orElseThrow();
            assertEquals("My saved search", retained.optString("name"));
            assertFalse(retained.optBoolean("enabled", true));
            assertEquals("Київ", retained.optJSONObject("filters").optString("city"));
            assertEquals(42000, retained.optJSONObject("filters").optInt("priceMax"));
            assertTrue(AutoChecks.hasTargets(db));
        } finally {
            db.close();
            context.deleteDatabase(fixture);
        }
    }

    @Test public void anotherOrdinarySearchRetainsGlobalDisableAndExplicitCurrentPause() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String fixture = "current-search-pause-test.db";
        context.deleteDatabase(fixture);
        Database db = new Database(context, fixture);
        try {
            db.saveState("settings", Json.object("background", false, "wifiOnly", true));
            JSONObject current = db.saveCurrentSearch(Json.object("city", "Київ", "kind", "both"));
            assertFalse(db.state("settings", new JSONObject()).optBoolean("background", true));
            Json.put(current, "enabled", false);
            db.saveSearch(current);
            db.saveCurrentSearch(Json.object("city", "Запоріжжя", "kind", "apartment"));
            assertEquals(1, db.searches().size());
            assertFalse(db.searches().get(0).optBoolean("enabled", true));
            assertEquals("Запоріжжя", db.searches().get(0).optJSONObject("filters").optString("city"));
            assertFalse(AutoChecks.hasTargets(db));
            JSONObject settings = db.state("settings", new JSONObject());
            assertFalse(settings.optBoolean("background", true));
            assertTrue(settings.optBoolean("wifiOnly"));
        } finally {
            db.close();
            context.deleteDatabase(fixture);
        }
    }

    @Test public void invalidOrdinarySearchDoesNotOverwriteThePersistedCurrentTarget() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String fixture = "current-search-validation-test.db";
        context.deleteDatabase(fixture);
        Database db = new Database(context, fixture);
        try {
            db.saveCurrentSearch(Json.object("city", "Київ", "kind", "house"));
            assertThrows(IllegalArgumentException.class,
                () -> db.saveCurrentSearch(Json.object("city", "Unknown", "kind", "house")));
            assertThrows(IllegalArgumentException.class,
                () -> db.saveCurrentSearch(Json.object("city", "Одеса", "kind", "castle")));
            assertEquals(1, db.searches().size());
            assertEquals("Київ", db.searches().get(0).optJSONObject("filters").optString("city"));
            assertEquals("house", db.searches().get(0).optJSONObject("filters").optString("kind"));
        } finally {
            db.close();
            context.deleteDatabase(fixture);
        }
    }
}

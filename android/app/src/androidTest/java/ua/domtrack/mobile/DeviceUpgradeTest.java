package ua.domtrack.mobile;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class DeviceUpgradeTest {

    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void test01MigrationKeepsUserState() {
        Context c = context();
        String name = "migration-test.db";
        c.deleteDatabase(name);
        SQLiteDatabase old = c.openOrCreateDatabase(name, 0, null);
        old.execSQL("CREATE TABLE state(name TEXT PRIMARY KEY,data TEXT NOT NULL)");
        old.execSQL("INSERT INTO state VALUES('favorite-test','{\"kept\":true}')");
        old.setVersion(1);
        old.close();
        try (Database db = new Database(c, name)) {
            assertTrue(db.state("favorite-test", new JSONObject()).optBoolean("kept"));
            float[] vector = new float[384];
            vector[0] = 1;
            db.saveEmbedding("test", vector);
            assertEquals(1, db.embedding("test")[0], 0);
        }
        c.deleteDatabase(name);
    }

    @Test
    public void test02RealNeuralInferenceAndOfficialTokenizerAgree() throws Exception {
        String raw = new String(
            InstrumentationRegistry.getInstrumentation()
                .getContext()
                .getAssets()
                .open("tokenizer-golden.json")
                .readAllBytes(),
            StandardCharsets.UTF_8
        );
        try (SemanticModel model = new SemanticModel(context())) {
            for (JSONObject row : Json.objects(new JSONArray(raw))) {
                long[] actual = model.tokens(row.optString("text"));
                JSONArray expected = row.optJSONArray("ids");
                assertEquals(
                    "Token count: " + row.optString("text"),
                    expected.length(),
                    actual.length
                );
                for (int i = 0; i < actual.length; i++) assertEquals(
                    "Token " + i + " in " + row.optString("text"),
                    expected.optLong(i),
                    actual[i]
                );
            }
            float[] a = model.embed("Продаю двухкомнатную квартиру с балконом и видом на парк."),
                b = model.embed("Продається двокімнатна квартира з балконом і краєвидом на парк."),
                other = model.embed(
                    "Рецепт шоколадного торта: взбить яйца, добавить муку и сахар."
                );
            double similar = SemanticModel.cosine(a, b),
                different = SemanticModel.cosine(a, other);
            assertTrue(
                "Multilingual paraphrases must be closer: " + similar + " vs " + different,
                similar > different + .15
            );
            Database db = ((DomTrackApp) context().getApplicationContext()).db();
            db.saveState(
                "aiDeviceTest",
                Json.object(
                    "model",
                    SemanticModel.VERSION,
                    "dimensions",
                    a.length,
                    "relatedCosine",
                    similar,
                    "unrelatedCosine",
                    different,
                    "goldenSamples",
                    new JSONArray(raw).length()
                )
            );
        }
    }

    @Test
    public void test03NativeMapHasPriceMarkersAndTouchRectangle() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context c = context();
        Database db = ((DomTrackApp) c.getApplicationContext()).db();
        JSONObject synthetic = Json.object(
            "url",
            "https://example.org/synthetic-map-1",
            "title",
            "Тест карты",
            "city",
            "Запоріжжя",
            "kind",
            "apartment",
            "deal",
            "sale",
            "price",
            12345,
            "currency",
            "USD",
            "latitude",
            47.8388,
            "longitude",
            35.1396,
            "observedAt",
            Model.now()
        );
        db.ingest(List.of(synthetic), false);
        MapActivity activity = (MapActivity) instrumentation.startActivitySync(
            new Intent(c, MapActivity.class)
                .putExtra(
                    "filters",
                    Json.object(
                        "city",
                        "Запоріжжя",
                        "kind",
                        "apartment",
                        "currency",
                        "USD"
                    ).toString()
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        UiDevice device = UiDevice.getInstance(instrumentation);
        try {
            java.lang.reflect.Field loaded = MapActivity.class.getDeclaredField("loaded");
            loaded.setAccessible(true);
            long deadline = System.currentTimeMillis() + 45000;
            boolean ready = false;
            while (System.currentTimeMillis() < deadline) {
                if (loaded.getBoolean(activity)) {
                    ready = true;
                    break;
                }
                Thread.sleep(500);
            }
            assertTrue("Native vector map did not load", ready);
            assertTrue(device.takeScreenshot(new java.io.File(c.getFilesDir(), "map-native.png")));
            device
                .findObject(new androidx.test.uiautomator.UiSelector().text("Прямоугольник"))
                .click();
            android.graphics.Rect bounds = device
                .findObject(
                    new androidx.test.uiautomator.UiSelector().description(
                        "Граница территории поиска"
                    )
                )
                .getBounds();
            device.swipe(
                bounds.left + bounds.width() / 4,
                bounds.top + bounds.height() / 4,
                bounds.right - bounds.width() / 4,
                bounds.bottom - bounds.height() / 4,
                20
            );
            java.lang.reflect.Field field = MapActivity.class.getDeclaredField("zone");
            field.setAccessible(true);
            assertEquals(
                "Rectangle must contain four corners",
                4,
                ((List<?>) field.get(activity)).size()
            );
            device.findObject(new androidx.test.uiautomator.UiSelector().text("Применить")).click();
            Thread.sleep(500);
            assertTrue("Polygon could not be applied", activity.isFinishing());
        } finally {
            instrumentation.runOnMainSync(activity::finish);
            db
                .getWritableDatabase()
                .delete("history", "listing=?", new String[] { Model.identity(synthetic) });
            db
                .getWritableDatabase()
                .delete("listing", "id=?", new String[] { Model.identity(synthetic) });
        }
    }

    @Test
    public void test04PublicRiaExampleLoadsOnPhoneParser() throws Exception {
        Database db = ((DomTrackApp) context().getApplicationContext()).db();
        int count = new Scanner(context(), db).importUrl(
            "https://dom.ria.com/uk/realty-prodaja-kvartira-zaporoje-shevchenkovskiy-bryullova-ulitsa-35044466.html"
        );
        assertEquals(1, count);
        assertTrue(
            db
                .listings()
                .stream()
                .anyMatch(
                    r -> r.optString("externalId").equals("35044466") && r.optInt("rooms") == 2
                )
        );
    }

    @Test
    public void test05FullSearchAdvancesWithScreenOffAndPersistsCursor() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context c = context();
        Activity activity = instrumentation.startActivitySync(
            new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        Database db = ((DomTrackApp) c.getApplicationContext()).db();
        JSONObject settings = db.state("settings", new JSONObject());
        Json.put(settings, "localAi", false);
        db.saveState("settings", settings);
        UiDevice device = UiDevice.getInstance(instrumentation);
        try {
            instrumentation.runOnMainSync(() ->
                SearchService.start(
                    c,
                    Json.object("city", "Запоріжжя", "kind", "apartment", "currency", "EUR")
                )
            );
            Thread.sleep(8000);
            assertTrue("Foreground search did not start", SearchService.RUNNING.get());
            int before = pages(db);
            device.sleep();
            Thread.sleep(16000);
            assertFalse("Screen should remain off", device.isScreenOn());
            int after = pages(db);
            assertTrue(
                "Search did not advance while screen was off: " + before + " -> " + after,
                after > before
            );
            assertTrue("Full search must progress beyond four pages", after > 4);
            assertTrue(
                "Foreground notification missing",
                Arrays.stream(
                    c.getSystemService(NotificationManager.class).getActiveNotifications()
                ).anyMatch(n -> n.getId() == SearchService.NOTIFICATION)
            );
            db.saveState(
                "screenOffTest",
                Json.object("pagesBefore", before, "pagesAfter", after, "screenStayedOff", true)
            );
        } finally {
            SearchService.stop(c);
            long until = System.currentTimeMillis() + 25000;
            while (SearchService.RUNNING.get() && System.currentTimeMillis() < until) Thread.sleep(
                250
            );
            device.wakeUp();
            instrumentation.runOnMainSync(activity::finish);
            Json.put(settings, "localAi", true);
            db.saveState("settings", settings);
        }
        assertFalse("Explicit stop did not terminate the search", SearchService.RUNNING.get());
    }

    @Test
    public void test06NewCatalogsReadRealPublicPages() throws Exception {
        Database db = ((DomTrackApp) context().getApplicationContext()).db();
        Network network = new Network();
        JSONObject checks = new JSONObject();
        for (String provider : List.of("flatfy", "lun")) {
            Sources.Section section = Sources.sections("Запоріжжя", "apartment")
                .stream()
                .filter(s -> s.source.equals(provider))
                .findFirst()
                .orElseThrow();
            assertTrue(
                provider + " first page restricted by robots.txt",
                network.robotsAllowed(section.base, db)
            );
            Network.Page first = network.get(section.base);
            Sources.Result a = Sources.parse(first.body, first.url, section, 1);
            assertTrue(provider + " must return public sale cards", a.records.size() > 0);
            assertTrue(
                provider + " must provide actual coordinates",
                a.records.stream().anyMatch(r -> r.has("latitude") && r.has("longitude"))
            );
            Json.put(
                checks,
                provider,
                Json.object("firstPageRecords", a.records.size(), "hasCoordinates", true)
            );
            if (provider.equals("flatfy")) {
                assertTrue(
                    "Flatfy pagination must be permitted",
                    network.robotsAllowed(section.page(2), db)
                );
                Network.Page second = network.get(section.page(2));
                Sources.Result b = Sources.parse(second.body, second.url, section, 2);
                assertTrue("Flatfy page 2 must return records", b.records.size() > 0);
                assertNotEquals(
                    "Flatfy page 2 must differ from page 1",
                    a.fingerprint,
                    b.fingerprint
                );
                Json.put(checks.getJSONObject(provider), "secondPageRecords", b.records.size());
            } else {
                Json.put(
                    checks.getJSONObject(provider),
                    "paginationAllowed",
                    network.robotsAllowed(section.page(2), db)
                );
                assertFalse("LUN first page cannot imply full coverage", a.end);
            }
        }
        for (String provider : List.of("flatfy", "lun")) {
            Sources.Section section = Sources.sections("Запоріжжя", "house")
                .stream()
                .filter(x -> x.source.equals(provider))
                .findFirst()
                .orElseThrow();
            assertTrue(network.robotsAllowed(section.base, db));
            Network.Page response = network.get(section.base);
            Sources.Result houses = Sources.parse(response.body, response.url, section, 1);
            assertTrue(provider + " must return house sale cards", houses.records.size() > 0);
            assertTrue(houses.records.stream().allMatch(r -> r.optString("kind").equals("house")));
            Json.put(checks.getJSONObject(provider), "houseRecords", houses.records.size());
        }
        db.saveState("newSourcesTest", checks);
    }

    private int pages(Database db) {
        int pages = 0;
        for (Sources.Section s : Sources.sections("Запоріжжя", "apartment"))
            pages += db.state("section:" + s.key(), new JSONObject()).optInt("lastPage");
        return pages;
    }
}

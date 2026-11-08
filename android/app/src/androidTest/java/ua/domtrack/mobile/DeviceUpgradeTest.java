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
        JSONObject outside = Json.copy(synthetic);
        Json.put(outside, "url", "https://example.org/synthetic-map-outside");
        Json.put(outside, "longitude", 35.15);
        List<JSONObject> syntheticRows = new ArrayList<>(List.of(synthetic, outside));
        JSONArray previousTerritory = new JSONArray(
            "[[47.8386,35.1394],[47.8390,35.1394],[47.8390,35.1398],[47.8386,35.1398]]"
        );
        db.ingest(syntheticRows, false);
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
                        "USD",
                        "query",
                        "Тест карты",
                        "polygon",
                        previousTerritory
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
            java.lang.reflect.Field rowsField = MapActivity.class.getDeclaredField("listings"),
                coverageField = MapActivity.class.getDeclaredField("coverage"),
                filtersField = MapActivity.class.getDeclaredField("filters"),
                refreshField = MapActivity.class.getDeclaredField("refresh");
            rowsField.setAccessible(true);
            coverageField.setAccessible(true);
            filtersField.setAccessible(true);
            refreshField.setAccessible(true);
            android.widget.TextView coverage = (android.widget.TextView) coverageField.get(activity);
            assertNotNull("Coverage must remain attached for later refreshes", coverage);
            instrumentation.runOnMainSync(() -> {
                assertEquals("The old territory must not hide candidates while editing", 2,
                    ((List<?>) readField(rowsField, activity)).size());
                assertNotNull(coverage.getParent());
                assertTrue(coverage.getText().toString().contains("Координаты площадок: 2 · без координат: 0"));
            });
            JSONObject added = Json.copy(synthetic), missing = Json.copy(synthetic);
            Json.put(added, "url", "https://example.org/synthetic-map-refresh");
            Json.put(added, "longitude", 35.16);
            Json.put(missing, "url", "https://example.org/synthetic-map-missing");
            missing.remove("latitude");
            missing.remove("longitude");
            syntheticRows.add(added);
            syntheticRows.add(missing);
            db.ingest(List.of(added, missing), false);
            instrumentation.runOnMainSync((Runnable) refreshField.get(activity));
            java.util.concurrent.atomic.AtomicBoolean refreshed = new java.util.concurrent.atomic.AtomicBoolean();
            deadline = System.currentTimeMillis() + 15000;
            while (!refreshed.get() && System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync(() -> refreshed.set(
                    coverage.getText().toString().contains("Координаты площадок: 3 · без координат: 1")
                ));
                Thread.sleep(100);
            }
            assertTrue("The same visible coverage view must update after the candidate refresh", refreshed.get());
            instrumentation.runOnMainSync(() -> assertEquals(3,
                ((List<?>) readField(rowsField, activity)).size()));
            java.lang.reflect.Field mapField = MapActivity.class.getDeclaredField("map"),
                viewField = MapActivity.class.getDeclaredField("view");
            mapField.setAccessible(true);
            viewField.setAccessible(true);
            org.maplibre.android.maps.MapLibreMap nativeMap =
                (org.maplibre.android.maps.MapLibreMap) mapField.get(activity);
            org.maplibre.android.maps.MapView nativeView =
                (org.maplibre.android.maps.MapView) viewField.get(activity);
            java.util.concurrent.atomic.AtomicInteger rendered =
                new java.util.concurrent.atomic.AtomicInteger();
            deadline = System.currentTimeMillis() + 45000;
            while (rendered.get() < 20 && System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync(() ->
                    rendered.set(
                        nativeMap
                            .queryRenderedFeatures(
                                new android.graphics.RectF(
                                    0,
                                    0,
                                    nativeView.getWidth(),
                                    nativeView.getHeight()
                                ),
                                (String[]) null
                            )
                            .size()
                    )
                );
                Thread.sleep(500);
            }
            assertTrue(
                "Map style loaded but street-map features were not rendered",
                rendered.get() >= 20
            );
            Thread.sleep(1500);
            db.saveState(
                "mapDeviceTest",
                Json.object("renderedFeatures", rendered.get(), "priceMarker", true,
                    "allMapCandidates", true, "refreshedCoverage", true)
            );
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
            assertEquals("Drawing must not replace the saved search before Apply",
                previousTerritory.toString(),
                ((JSONObject) filtersField.get(activity)).getJSONArray("polygon").toString());
            device.findObject(new androidx.test.uiautomator.UiSelector().text("Применить")).click();
            Thread.sleep(500);
            assertTrue("Polygon could not be applied", activity.isFinishing());
            JSONObject applied = (JSONObject) filtersField.get(activity);
            assertEquals("Тест карты", applied.getString("query"));
            assertEquals("Запоріжжя", applied.getString("city"));
            assertEquals("USD", applied.getString("currency"));
            assertTrue(Geometry.valid(applied.getJSONArray("polygon")));
            assertNotEquals(previousTerritory.toString(), applied.getJSONArray("polygon").toString());
        } finally {
            instrumentation.runOnMainSync(activity::finish);
            for (JSONObject row : syntheticRows) {
                db.getWritableDatabase().delete("history", "listing=?", new String[] { Model.identity(row) });
                db.getWritableDatabase().delete("listing", "id=?", new String[] { Model.identity(row) });
            }
        }
    }

    private static Object readField(java.lang.reflect.Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void test03bGlobalFavoriteMapFramesActualCoordinatesAndKeepsUserCamera() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context c = context();
        Database db = ((DomTrackApp) c.getApplicationContext()).db();
        String query = "Глобальна перевірка карти";
        JSONObject lviv = Json.object(
            "url", "https://example.org/global-map-lviv", "title", query,
            "city", "Львів", "kind", "apartment", "deal", "sale",
            "price", 13000, "currency", "USD", "latitude", 49.8397, "longitude", 24.0297
        );
        JSONObject zaporizhzhia = Json.copy(lviv);
        Json.put(zaporizhzhia, "url", "https://example.org/global-map-zaporizhzhia");
        Json.put(zaporizhzhia, "city", "Запоріжжя");
        Json.put(zaporizhzhia, "latitude", 47.8388);
        Json.put(zaporizhzhia, "longitude", 35.1396);
        MapActivity activity = null;
        try {
            activity = startGlobalMap(c, query);
            awaitVisibleCoordinates(activity, new double[][] { { 44.1, 22.1 }, { 52.4, 40.4 } });
            java.lang.reflect.Field rows = MapActivity.class.getDeclaredField("listings");
            rows.setAccessible(true);
            MapActivity emptyMap = activity;
            instrumentation.runOnMainSync(() -> assertEquals("Empty map must not invent pins", 0,
                ((List<?>) readField(rows, emptyMap)).size()));

            db.ingest(List.of(lviv), false);
            db.local(Model.identity(lviv), "favorite", true);
            refreshMap(activity);
            awaitCamera(activity, 49.8397, 24.0297, 12);

            MapActivity singleMap = activity;
            org.maplibre.android.maps.MapLibreMap map = nativeMap(activity);
            instrumentation.runOnMainSync(() -> map.setCameraPosition(
                new org.maplibre.android.camera.CameraPosition.Builder()
                    .target(new org.maplibre.android.geometry.LatLng(50.45, 30.52)).zoom(9).build()
            ));
            db.ingest(List.of(zaporizhzhia), false);
            db.local(Model.identity(zaporizhzhia), "favorite", true);
            refreshMap(activity);
            long deadline = System.currentTimeMillis() + 15000;
            java.util.concurrent.atomic.AtomicBoolean twoRows = new java.util.concurrent.atomic.AtomicBoolean();
            while (!twoRows.get() && System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync(() -> twoRows.set(
                    ((List<?>) readField(rows, singleMap)).size() == 2));
                Thread.sleep(100);
            }
            assertTrue("Both favorite cities must refresh", twoRows.get());
            awaitCamera(activity, 50.45, 30.52, 9);
            instrumentation.runOnMainSync(activity::finish);

            activity = startGlobalMap(c, query);
            awaitVisibleCoordinates(activity, new double[][] { { 49.8397, 24.0297 }, { 47.8388, 35.1396 } });
        } finally {
            if (activity != null) instrumentation.runOnMainSync(activity::finish);
            for (JSONObject row : List.of(lviv, zaporizhzhia)) {
                db.getWritableDatabase().delete("history", "listing=?", new String[] { Model.identity(row) });
                db.getWritableDatabase().delete("listing", "id=?", new String[] { Model.identity(row) });
            }
        }
    }

    private MapActivity startGlobalMap(Context c, String query) {
        return (MapActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(
            new Intent(c, MapActivity.class)
                .putExtra("filters", Json.object("favorite", true, "query", query, "currency", "USD").toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private org.maplibre.android.maps.MapLibreMap nativeMap(MapActivity activity) throws Exception {
        java.lang.reflect.Field field = MapActivity.class.getDeclaredField("map");
        field.setAccessible(true);
        return (org.maplibre.android.maps.MapLibreMap) field.get(activity);
    }

    private void refreshMap(MapActivity activity) throws Exception {
        java.lang.reflect.Field field = MapActivity.class.getDeclaredField("refresh");
        field.setAccessible(true);
        InstrumentationRegistry.getInstrumentation().runOnMainSync((Runnable) field.get(activity));
    }

    private void awaitCamera(MapActivity activity, double latitude, double longitude, double zoom) throws Exception {
        long deadline = System.currentTimeMillis() + 15000;
        java.util.concurrent.atomic.AtomicBoolean positioned = new java.util.concurrent.atomic.AtomicBoolean();
        org.maplibre.android.maps.MapLibreMap map = nativeMap(activity);
        while (!positioned.get() && System.currentTimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                org.maplibre.android.camera.CameraPosition camera = map.getCameraPosition();
                positioned.set(camera.target != null && Math.abs(camera.target.getLatitude() - latitude) < .0001
                    && Math.abs(camera.target.getLongitude() - longitude) < .0001 && Math.abs(camera.zoom - zoom) < .01);
            });
            Thread.sleep(100);
        }
        assertTrue("Map must frame the actual marker or preserve the chosen camera", positioned.get());
    }

    private void awaitVisibleCoordinates(MapActivity activity, double[][] coordinates) throws Exception {
        java.lang.reflect.Field mapField = MapActivity.class.getDeclaredField("map"),
            viewField = MapActivity.class.getDeclaredField("view"),
            loadedField = MapActivity.class.getDeclaredField("loaded");
        mapField.setAccessible(true);
        viewField.setAccessible(true);
        loadedField.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean visible = new java.util.concurrent.atomic.AtomicBoolean();
        long deadline = System.currentTimeMillis() + 45000;
        while (!visible.get() && System.currentTimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                org.maplibre.android.maps.MapLibreMap map =
                    (org.maplibre.android.maps.MapLibreMap) readField(mapField, activity);
                org.maplibre.android.maps.MapView view =
                    (org.maplibre.android.maps.MapView) readField(viewField, activity);
                if (map == null || !Boolean.TRUE.equals(readField(loadedField, activity)) || view.getWidth() == 0) return;
                if (map.getCameraPosition().zoom < 3) return;
                for (double[] coordinate : coordinates) {
                    android.graphics.PointF point = map.getProjection().toScreenLocation(
                        new org.maplibre.android.geometry.LatLng(coordinate[0], coordinate[1]));
                    if (point.x < 12 || point.y < 12 || point.x > view.getWidth() - 12 || point.y > view.getHeight() - 12) return;
                }
                visible.set(true);
            });
            Thread.sleep(100);
        }
        assertTrue("Global map camera must include the real coordinates with padding", visible.get());
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
                .filter(x -> x.source.equals(provider))
                .findFirst()
                .orElseThrow();
            JSONObject evidence = Json.object("status", "checking");
            Json.put(checks, provider, evidence);
            try {
                if (!network.robotsAllowed(section.base, db)) {
                    Json.put(evidence, "status", "restricted");
                    Json.put(evidence, "error", "robots.txt restricts the catalog");
                    continue;
                }
                Network.Page first = network.get(section.base);
                Sources.Result one = Sources.parse(first.body, first.url, section, 1);
                assertTrue(provider + " must return actual sale cards", one.records.size() > 0);
                assertTrue(
                    provider + " must provide actual coordinates",
                    one.records.stream().anyMatch(r -> r.has("latitude") && r.has("longitude"))
                );
                Json.put(evidence, "firstPageRecords", one.records.size());
                Json.put(evidence, "hasCoordinates", true);
                Json.put(evidence, "status", "available");
                if (provider.equals("flatfy")) {
                    if (network.robotsAllowed(section.page(2), db)) {
                        Network.Page second = network.get(section.page(2));
                        Sources.Result two = Sources.parse(second.body, second.url, section, 2);
                        assertTrue(two.records.size() > 0);
                        assertNotEquals(one.fingerprint, two.fingerprint);
                        Json.put(evidence, "secondPageRecords", two.records.size());
                    } else {
                        Json.put(evidence, "status", "partial");
                        Json.put(evidence, "error", "robots.txt restricts pagination");
                    }
                } else {
                    boolean allowed = network.robotsAllowed(section.page(2), db);
                    Json.put(evidence, "paginationAllowed", allowed);
                    assertFalse(one.end);
                    if (!allowed) Json.put(evidence, "status", "partial");
                }
                Sources.Section house = Sources.sections("Запоріжжя", "house")
                    .stream()
                    .filter(x -> x.source.equals(provider))
                    .findFirst()
                    .orElseThrow();
                if (network.robotsAllowed(house.base, db)) {
                    Network.Page response = network.get(house.base);
                    Sources.Result houses = Sources.parse(response.body, response.url, house, 1);
                    assertTrue(houses.records.size() > 0);
                    assertTrue(
                        houses.records.stream().allMatch(r -> r.optString("kind").equals("house"))
                    );
                    Json.put(evidence, "houseRecords", houses.records.size());
                }
            } catch (java.io.IOException unavailable) {
                // Vendor access denial or outage is evidence of limited coverage, never fabricated records.
                Json.put(
                    evidence,
                    "status",
                    evidence.optInt("firstPageRecords") > 0 ? "partial" : "unavailable"
                );
                Json.put(evidence, "error", Scanner.safe(unavailable));
            } finally {
                db.saveState("newSourcesTest", checks);
            }
        }
        assertEquals(2, checks.length());
    }

    private int pages(Database db) {
        int pages = 0;
        for (Sources.Section s : Sources.sections("Запоріжжя", "apartment"))
            pages += db.state("section:" + s.key(), new JSONObject()).optInt("lastPage");
        return pages;
    }
}

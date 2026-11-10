package ua.domtrack.mobile;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.RectF;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.*;
import org.maplibre.android.style.layers.*;
import org.maplibre.android.style.sources.VectorSource;

/** Synthetic crowded map data only; run on an emulator with native vector-map networking. */
@RunWith(AndroidJUnit4.class)
public class MapPriceDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void crowdedChipOpensScrollableExactPricesAndIndividualCard() throws Exception {
        Context context = instrumentation.getTargetContext();
        Database db = ((DomTrackApp) context.getApplicationContext()).db();
        JSONObject previousMapUi = db.state("mapUi", new JSONObject());
        db.saveState("mapUi", new JSONObject());
        ArrayList<JSONObject> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++) rows.add(Json.object(
            "url", "https://example.org/map-price-group-fixture-" + i,
            "title", "Синтетический объект карты " + i,
            "city", "MapPriceSynthetic", "kind", i % 2 == 0 ? "apartment" : "house",
            "deal", "sale", "source", "manual", "currency", "USD", "price", 23500 + i * 1100,
            "address", "Тестовая улица, " + (i + 1), "rooms", i + 1, "area", 40 + i * 7,
            "latitude", 47.8388 + i * .000002, "longitude", 35.1396 + i * .000002,
            "observedAt", Model.now()));
        db.ingest(rows, false);
        MapActivity activity = null;
        UiDevice device = UiDevice.getInstance(instrumentation);
        try {
            activity = (MapActivity) instrumentation.startActivitySync(new Intent(context, MapActivity.class)
                .putExtra("filters", Json.object("city", "MapPriceSynthetic", "currency", "USD", "polygon",
                    new org.json.JSONArray("[[47.837,35.138],[47.837,35.141],[47.840,35.141],[47.840,35.138]]")).toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            MapActivity current = activity;
            await(() -> Boolean.TRUE.equals(field(current, "loaded")), "Native map style did not load");
            instrumentation.runOnMainSync(() -> ((MapLibreMap) field(current, "map")).setCameraPosition(
                new CameraPosition.Builder().target(new LatLng(47.8388, 35.1396)).zoom(15).build()));
            awaitStreetFeatures(current);
            assertEquals("Detailed Bright is the default map", "bright", field(current, "mapStyle"));
            AtomicReference<CameraPosition> cameraBefore = new AtomicReference<>();
            AtomicReference<List<LatLng>> territoryBefore = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                cameraBefore.set(((MapLibreMap) field(current, "map")).getCameraPosition());
                territoryBefore.set(new ArrayList<>((List<LatLng>) field(current, "zone")));
            });
            device.findObject(new UiSelector().text("Вид карты")).click();
            device.findObject(new UiSelector().textStartsWith("Liberty")).click();
            // A second choice before the first style finishes must keep the latest choice active.
            device.findObject(new UiSelector().text("Вид карты")).click();
            device.findObject(new UiSelector().textStartsWith("Positron")).click();
            await(() -> "positron".equals(field(current, "mapStyle")) &&
                Boolean.TRUE.equals(field(current, "loaded")), "Latest selected map style did not load");
            awaitStreetFeatures(current);
            assertEquals("positron", db.state("mapUi", new JSONObject()).optString("style"));
            instrumentation.runOnMainSync(() -> {
                CameraPosition after = ((MapLibreMap) field(current, "map")).getCameraPosition();
                assertEquals(cameraBefore.get().target.getLatitude(), after.target.getLatitude(), .000001);
                assertEquals(cameraBefore.get().target.getLongitude(), after.target.getLongitude(), .000001);
                assertEquals(cameraBefore.get().zoom, after.zoom, .001);
                assertEquals(territoryBefore.get(), field(current, "zone"));
                CharSequence attribution = ((android.widget.TextView) field(current, "coverage")).getText();
                assertTrue(attribution.toString().contains("© OpenStreetMap contributors"));
                android.text.style.URLSpan[] links = ((android.text.Spanned) attribution).getSpans(
                    0, attribution.length(), android.text.style.URLSpan.class);
                assertTrue(Arrays.stream(links).anyMatch(link -> link.getURL().equals("https://openmaptiles.org")));
            });
            assertTrue(device.takeScreenshot(new java.io.File(context.getFilesDir(), "map-price-style-positron.png")));
            AtomicReference<RectF> chip = new AtomicReference<>();
            await(() -> {
                View drawing = (View) field(current, "drawing");
                List<?> hits = (List<?>) field(drawing, "hitGroups");
                List<?> boxes = (List<?>) field(drawing, "hitBoxes");
                if (hits.size() != 1 || ((List<?>) hits.get(0)).size() != 12) return false;
                chip.set(new RectF((RectF) boxes.get(0)));
                assertEquals("All filtered candidates remain on the map", 12, ((List<?>) field(current, "listings")).size());
                return true;
            }, "Crowded candidates must become one tappable chip with all members");
            device.waitForIdle();
            assertTrue("DomTrack map header remains visible", device.findObject(new UiSelector().text("DomTrack")).exists());
            assertTrue(device.takeScreenshot(new java.io.File(context.getFilesDir(), "map-price-groups.png")));
            AtomicReference<int[]> location = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                int[] xy = new int[2]; ((View) field(current, "drawing")).getLocationOnScreen(xy); location.set(xy);
            });
            assertTrue(device.click(location.get()[0] + Math.round(chip.get().centerX()),
                location.get()[1] + Math.round(chip.get().centerY())));
            UiObject first = device.findObject(new UiSelector().className("android.widget.Button")
                .textContains("Тестовая улица, 1\n"));
            assertTrue("Group tap must show full prices, not abbreviated map labels", first.waitForExists(5000));
            assertTrue(first.getText().replace('\u00a0', ' ').replace('\u202f', ' ').contains("23 500 USD"));
            assertTrue(first.getText().contains("40 м²"));
            assertTrue(device.takeScreenshot(new java.io.File(context.getFilesDir(), "map-price-group-list.png")));
            UiScrollable list = new UiScrollable(new UiSelector().description("Список объектов группы"));
            assertTrue(list.scrollIntoView(new UiSelector().className("android.widget.Button")
                .textContains("Тестовая улица, 12")));
            UiObject finalRow = device.findObject(new UiSelector().className("android.widget.Button")
                .textContains("Тестовая улица, 12"));
            assertTrue("Every group member must be reachable by scrolling", finalRow.exists());
            finalRow.click();
            await(current::isFinishing, "Selecting a group row must return the individual object card");

            activity = (MapActivity) instrumentation.startActivitySync(new Intent(context, MapActivity.class)
                .putExtra("filters", Json.object("currency", "USD").toString())
                .putExtra("selectedListingId", Model.identity(rows.get(0)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            MapActivity selected = activity;
            await(() -> Boolean.TRUE.equals(field(selected, "loaded")), "Selected native map style did not load");
            assertEquals("Selected-object map must retain the chosen style", "positron", field(selected, "mapStyle"));
            await(() -> {
                CameraPosition camera = ((MapLibreMap) field(selected, "map")).getCameraPosition();
                return camera.target != null && Math.abs(camera.target.getLatitude() - 47.8388) < .00001 &&
                    Math.abs(camera.target.getLongitude() - 35.1396) < .00001 && Math.abs(camera.zoom - 16) < .001 &&
                    Math.abs(camera.tilt - 45) < .001;
            }, "Selected style must still frame the real object");
            awaitStreetFeatures(selected);
            await(() -> renderedBuildings(selected) > 0, "Selected 3D map must render actual OMT building volumes");
            instrumentation.runOnMainSync(() -> {
                Style style = ((MapLibreMap) field(selected, "map")).getStyle();
                MapBuildings.apply(style);
                assertEquals("Applying buildings twice must not duplicate the layer", 1,
                    style.getLayers().stream().filter(layer -> MapBuildings.LAYER_ID.equals(layer.getId())).count());
                FillExtrusionLayer buildings = (FillExtrusionLayer) style.getLayer(MapBuildings.LAYER_ID);
                assertEquals("building", buildings.getSourceLayer());
                assertTrue(style.getSource(buildings.getSourceId()) instanceof VectorSource);
                int buildingIndex = -1, firstLabel = Integer.MAX_VALUE;
                List<Layer> layers = style.getLayers();
                for (int i = 0; i < layers.size(); i++) {
                    if (MapBuildings.LAYER_ID.equals(layers.get(i).getId())) buildingIndex = i;
                    if (layers.get(i) instanceof SymbolLayer) firstLabel = Math.min(firstLabel, i);
                }
                assertTrue("Street labels must remain above 3D buildings", buildingIndex >= 0 && buildingIndex < firstLabel);
                assertTrue(((android.widget.TextView) field(selected, "selectedPrice")).getText().toString()
                    .replace('\u00a0', ' ').replace('\u202f', ' ').contains("23 500 USD"));
                assertTrue(((android.widget.TextView) field(selected, "selectedAddress")).getText().toString()
                    .contains("Тестовая улица, 1"));
                assertTrue(((android.widget.TextView) field(selected, "selectedSource")).getText().toString()
                    .contains("Вручную"));
                assertTrue(((android.widget.TextView) field(selected, "selectedQuality")).getText().toString()
                    .contains("точность"));
                assertTrue(((View) field(selected, "routeButton")).isEnabled());
                assertTrue(((View) field(selected, "recordButton")).isEnabled());
            });
            device.waitForIdle();
            assertTrue(device.takeScreenshot(new java.io.File(context.getFilesDir(), "map-price-selected-polish.png")));
            AtomicReference<CameraPosition> beforeFlat = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> beforeFlat.set(((MapLibreMap) field(selected, "map")).getCameraPosition()));
            device.findObject(new UiSelector().text("2D / 3D")).click();
            await(() -> {
                MapLibreMap map = (MapLibreMap) field(selected, "map");
                Layer buildings = map.getStyle().getLayer(MapBuildings.LAYER_ID);
                CameraPosition camera = map.getCameraPosition();
                assertEquals(beforeFlat.get().target, camera.target);
                assertEquals(beforeFlat.get().zoom, camera.zoom, .001);
                return Math.abs(camera.tilt) < .001 && Property.NONE.equals(buildings.getVisibility().getValue()) &&
                    renderedBuildings(selected) == 0;
            }, "2D must flatten the camera and hide building volumes without moving the object");
            assertTrue(device.takeScreenshot(new java.io.File(context.getFilesDir(), "map-price-selected-2d.png")));
            device.findObject(new UiSelector().text("2D / 3D")).click();
            await(() -> Math.abs(((MapLibreMap) field(selected, "map")).getCameraPosition().tilt - 45) < .001 &&
                renderedBuildings(selected) > 0, "3D toggle must restore actual building volumes");
            device.findObject(new UiSelector().text("Карточка")).click();
            await(selected::isFinishing, "Styled selected-map card action must still return the individual card");

            JSONObject missing = Json.copy(rows.get(0));
            Json.put(missing, "url", "https://example.org/map-price-group-fixture-no-coordinates");
            Json.put(missing, "city", "Запоріжжя");
            missing.remove("latitude"); missing.remove("longitude");
            rows.add(missing);
            db.ingest(List.of(missing), false);
            activity = (MapActivity) instrumentation.startActivitySync(new Intent(context, MapActivity.class)
                .putExtra("filters", Json.object("currency", "USD").toString())
                .putExtra("selectedListingId", Model.identity(missing))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            MapActivity withoutPoint = activity;
            await(() -> Boolean.TRUE.equals(field(withoutPoint, "loaded")), "Coordinate-free map style did not load");
            awaitStreetFeatures(withoutPoint);
            await(() -> {
                View drawing = (View) field(withoutPoint, "drawing");
                return ((List<?>) field(withoutPoint, "listings")).isEmpty() &&
                    ((List<?>) field(drawing, "hitGroups")).isEmpty() &&
                    !((View) field(withoutPoint, "perspectiveButton")).isEnabled() &&
                    Math.abs(((MapLibreMap) field(withoutPoint, "map")).getCameraPosition().tilt) < .001;
            }, "Missing coordinates must never invent a house point or enable object 3D");
            assertTrue(device.takeScreenshot(new java.io.File(context.getFilesDir(), "map-price-selected-no-point.png")));
        } finally {
            if (activity != null) {
                MapActivity opened = activity;
                instrumentation.runOnMainSync(opened::finish);
            }
            db.saveState("mapUi", previousMapUi);
            for (JSONObject row : rows) {
                String id = Model.identity(row);
                db.getWritableDatabase().delete("history", "listing=?", new String[] { id });
                db.getWritableDatabase().delete("listing", "id=?", new String[] { id });
            }
        }
    }

    private interface Check { boolean ready(); }

    private int renderedBuildings(MapActivity activity) {
        MapLibreMap map = (MapLibreMap) field(activity, "map");
        MapView view = (MapView) field(activity, "view");
        Layer layer = map.getStyle().getLayer(MapBuildings.LAYER_ID);
        assertTrue("Actual map style must contain the managed extrusion layer", layer instanceof FillExtrusionLayer);
        return map.queryRenderedFeatures(new RectF(0, 0, view.getWidth(), view.getHeight()), MapBuildings.LAYER_ID).size();
    }

    private void awaitStreetFeatures(MapActivity activity) throws Exception {
        AtomicInteger features = new AtomicInteger();
        await(() -> {
            MapView view = (MapView) field(activity, "view");
            features.set(((MapLibreMap) field(activity, "map")).queryRenderedFeatures(
                new RectF(0, 0, view.getWidth(), view.getHeight()), (String[]) null).size());
            return features.get() >= 20;
        }, "Style loaded without rendered street-map features");
    }

    private void await(Check check, String message) throws Exception {
        AtomicBoolean ready = new AtomicBoolean();
        long deadline = System.currentTimeMillis() + 45000;
        while (!ready.get() && System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync(() -> ready.set(check.ready()));
            Thread.sleep(200);
        }
        assertTrue(message, ready.get());
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}

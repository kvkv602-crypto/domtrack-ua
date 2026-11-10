package ua.domtrack.mobile;

import static org.junit.Assert.*;
import android.app.*;
import android.content.*;
import android.graphics.RectF;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.maps.*;

/** Native, synthetic-coordinate preview; verifies the lazy renderer and its resource lifecycle. */
@RunWith(AndroidJUnit4.class)
public class ListingMapPreviewDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void absentCoordinatesNeverCreateAMapOrASubstitutePin() {
        instrumentation.runOnMainSync(() -> {
            Context context = instrumentation.getTargetContext();
            for (JSONObject row : new JSONObject[] { new JSONObject(),
                    Json.object("latitude", 0, "longitude", 0),
                    Json.object("latitude", 200, "longitude", 30) }) {
                LinearLayout parent = new LinearLayout(context);
                ListingMapPreview.add(parent, row, () -> fail("Missing source coordinates must not open a preview"));
                assertEquals(1, parent.getChildCount());
                assertTrue(parent.getChildAt(0) instanceof TextView);
                assertTrue(((TextView) parent.getChildAt(0)).getText().toString().contains("координат"));
                assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, parent.getChildAt(0).getLayoutParams().height);
            }
        });
    }

    @Test public void visiblePreviewRendersActualPointAndBuildingsThenDestroysWhenHidden() throws Exception {
        Context context = instrumentation.getTargetContext();
        Database db = ((DomTrackApp) context.getApplicationContext()).db();
        JSONObject previousUi = db.state("mapUi", new JSONObject());
        db.saveState("mapUi", Json.object("style", "untrusted-style"));
        Activity activity = null;
        AtomicReference<AlertDialog> dialog = new AtomicReference<>();
        AtomicReference<ScrollView> scroll = new AtomicReference<>();
        AtomicReference<View> preview = new AtomicReference<>();
        AtomicInteger expanded = new AtomicInteger();
        UiDevice device = UiDevice.getInstance(instrumentation);
        try {
            device.wakeUp();
            activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity owner = activity;
            instrumentation.runOnMainSync(() -> {
                LinearLayout content = new LinearLayout(owner);
                content.setOrientation(LinearLayout.VERTICAL);
                View top = new View(owner);
                content.addView(top, new LinearLayout.LayoutParams(-1, owner.getResources().getDisplayMetrics().heightPixels));
                ListingMapPreview.add(content, Json.object("latitude", 47.8388, "longitude", 35.1396,
                    "locationAccuracy", "source-unspecified"), expanded::incrementAndGet);
                preview.set(content.getChildAt(1));
                content.addView(new View(owner), new LinearLayout.LayoutParams(-1,
                    owner.getResources().getDisplayMetrics().heightPixels));
                ScrollView viewport = new ScrollView(owner);
                viewport.addView(content);
                scroll.set(viewport);
                AlertDialog opened = new AlertDialog.Builder(owner).setView(viewport).create();
                dialog.set(opened);
                opened.show();
                opened.getWindow().setLayout(-1, -1);
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> assertNull("An offscreen preview must not initialize the native map",
                field(preview.get(), "mapView")));
            instrumentation.runOnMainSync(() -> scroll.get().scrollTo(0,
                owner.getResources().getDisplayMetrics().heightPixels));
            await(() -> Boolean.TRUE.equals(field(preview.get(), "loaded")), "Inline map style did not load");
            await(() -> {
                MapLibreMap map = (MapLibreMap) field(preview.get(), "map");
                MapView view = (MapView) field(preview.get(), "mapView");
                return map != null && view != null && map.queryRenderedFeatures(
                    new RectF(0, 0, view.getWidth(), view.getHeight()), (String[]) null).size() >= 20;
            }, "Inline map did not render street/building features");
            AtomicReference<MapView> nativeView = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                MapLibreMap map = (MapLibreMap) field(preview.get(), "map");
                nativeView.set((MapView) field(preview.get(), "mapView"));
                CameraPosition camera = map.getCameraPosition();
                assertEquals(47.8388, camera.target.getLatitude(), .000001);
                assertEquals(35.1396, camera.target.getLongitude(), .000001);
                assertEquals(16, camera.zoom, .001);
                assertEquals(45, camera.tilt, .001);
                assertFalse("The inline map must allow the enclosing page to scroll", map.getUiSettings().areAllGesturesEnabled());
                assertNotNull("3D buildings must use the shared native style layer", map.getStyle().getLayer(MapBuildings.LAYER_ID));
                assertEquals("bright", field(preview.get(), "style"));
                Button expand = (Button) find(preview.get(), "Развернуть карту");
                assertNotNull(expand);
                expand.performClick();
                assertEquals(1, expanded.get());
            });
            device.waitForIdle();
            assertTrue(device.takeScreenshot(new java.io.File(context.getFilesDir(), "listing-map-preview.png")));
            instrumentation.runOnMainSync(() -> scroll.get().scrollTo(0, 0));
            await(() -> field(preview.get(), "mapView") == null, "Scrolling the map offscreen must release its renderer");
            instrumentation.runOnMainSync(() -> assertTrue(nativeView.get().isDestroyed()));
            instrumentation.runOnMainSync(() -> scroll.get().scrollTo(0,
                owner.getResources().getDisplayMetrics().heightPixels));
            await(() -> Boolean.TRUE.equals(field(preview.get(), "loaded")), "A visible preview must be able to recreate its renderer");
            instrumentation.runOnMainSync(() -> nativeView.set((MapView) field(preview.get(), "mapView")));
            device.pressHome();
            await(() -> field(preview.get(), "mapView") == null, "A hidden app must not keep the inline renderer active");
            instrumentation.runOnMainSync(() -> assertTrue(nativeView.get().isDestroyed()));
        } finally {
            if (dialog.get() != null) instrumentation.runOnMainSync(dialog.get()::dismiss);
            if (activity != null) {
                Activity opened = activity;
                instrumentation.runOnMainSync(opened::finish);
            }
            db.saveState("mapUi", previousUi);
            device.wakeUp();
        }
    }

    private interface Check { boolean ready(); }
    private void await(Check check, String message) throws Exception {
        AtomicBoolean ready = new AtomicBoolean();
        long until = System.currentTimeMillis() + 45000;
        while (!ready.get() && System.currentTimeMillis() < until) {
            instrumentation.runOnMainSync(() -> ready.set(check.ready()));
            Thread.sleep(200);
        }
        assertTrue(message, ready.get());
    }
    private static View find(View root, String text) {
        if (root instanceof TextView label && label.getText().toString().equals(text)) return root;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View match = find(group.getChildAt(i), text);
            if (match != null) return match;
        }
        return null;
    }
    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}

package ua.domtrack.mobile;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.os.StrictMode;
import android.view.ViewGroup;
import android.view.View;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.FutureTask;
import java.lang.reflect.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Memory/lifecycle fixtures and public CI-listing photos through the real native image pipeline. */
@RunWith(AndroidJUnit4.class)
public class RemotePhotosDeviceTest {
    @Test public void windowOrParentHidingCancelsPendingWorkAndClearsTheDisplayedBitmap() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            LinearLayout parent = new LinearLayout(context);
            RemotePhotos.addThumbnail(parent, Json.object("image", "https://cdn.example.org/no-request.jpg"));
            ImageView photo = (ImageView) parent.getChildAt(0);
            try {
                Field generation = photo.getClass().getDeclaredField("generation");
                Field task = photo.getClass().getDeclaredField("task");
                Method hidden = photo.getClass().getDeclaredMethod("onWindowVisibilityChanged", int.class);
                generation.setAccessible(true);
                task.setAccessible(true);
                hidden.setAccessible(true);
                for (boolean window : new boolean[] { true, false }) {
                    Bitmap displayed = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
                    photo.setImageBitmap(displayed);
                    FutureTask<Void> pending = new FutureTask<>(() -> null);
                    task.set(photo, pending);
                    int previous = generation.getInt(photo);
                    assertNotNull(photo.getDrawable());
                    if (window) hidden.invoke(photo, View.INVISIBLE);
                    else photo.onVisibilityAggregated(false);
                    assertTrue("Hidden photos must cancel pending image work", pending.isCancelled());
                    assertTrue("Stale image callbacks must be invalidated", generation.getInt(photo) > previous);
                    assertNull(photo.getDrawable());
                    assertNull(task.get(photo));
                    displayed.recycle();
                }
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
    }

    @Test public void nativeDecodeDownsamplesInMemoryWithoutDiskWrites() throws Exception {
        Bitmap source = Bitmap.createBitmap(4096, 2048, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.rgb(30, 100, 180));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 85, bytes));
        source.recycle();
        assertThrows(IOException.class, () -> RemotePhotos.decode(new byte[0]));
        AtomicInteger writes = new AtomicInteger();
        StrictMode.ThreadPolicy old = StrictMode.getThreadPolicy();
        Bitmap decoded;
        try {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectDiskWrites()
                .penaltyListener(Runnable::run, violation -> writes.incrementAndGet()).build());
            decoded = RemotePhotos.decode(bytes.toByteArray());
        } finally { StrictMode.setThreadPolicy(old); }
        try {
            assertNotNull(decoded);
            assertTrue(Math.max(decoded.getWidth(), decoded.getHeight()) <= 1280);
            assertTrue(decoded.getAllocationByteCount() <= 1280 * 1280 * 4);
            assertEquals("Source-photo decoding must never create a disk cache", 0, writes.get());
        } finally { decoded.recycle(); }
    }

    @Test public void corruptAndOversizeImageBytesAreRejected() {
        assertThrows(IOException.class, () -> RemotePhotos.decode(new byte[] { 1, 2, 3, 4 }));
        assertThrows(IOException.class, () -> RemotePhotos.decode(new byte[3 * 1024 * 1024 + 1]));
    }

    @Test public void missingOrUnsafeMetadataLeavesOnlyASmallGalleryCaption() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            for (JSONObject row : new JSONObject[] { new JSONObject(), Json.object("image", "https://127.0.0.1/photo.jpg") }) {
                LinearLayout parent = new LinearLayout(context);
                parent.setOrientation(LinearLayout.VERTICAL);
                RemotePhotos.addThumbnail(parent, row);
                assertEquals(0, parent.getChildCount());
                RemotePhotos.addGallery(parent, row);
                assertEquals(1, parent.getChildCount());
                assertTrue(parent.getChildAt(0) instanceof TextView);
                TextView caption = (TextView) parent.getChildAt(0);
                assertTrue(caption.getText().toString().contains("исходном объявлении"));
                assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, caption.getLayoutParams().height);
            }
        });
    }

    @Test public void realPublicListingThumbnailLoadsThroughVisibleNativeView() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        Database db = ((DomTrackApp) context.getApplicationContext()).db();
        List<JSONObject> listings = new ArrayList<>(db.listings());
        listings.sort(Comparator.comparingInt(row -> "rieltor".equals(row.optString("source")) ? 0 : 1));
        LinkedHashMap<String, JSONObject> distinct = new LinkedHashMap<>();
        Set<String> publicSources = Set.of("rieltor", "olx", "domria-web", "domria", "lun", "flatfy", "domik", "obyava");
        for (JSONObject row : listings) {
            if (!publicSources.contains(row.optString("source"))) continue;
            List<String> urls = ListingPhotos.urls(row);
            if (!urls.isEmpty()) distinct.putIfAbsent(urls.get(0), row);
            if (distinct.size() == 3) break;
        }
        List<JSONObject> candidates = new ArrayList<>(distinct.values());
        assertFalse("DeviceUpgrade must first import real public listing photo metadata; listing count=" + listings.size(), candidates.isEmpty());
        JSONObject sources = new JSONObject();
        for (JSONObject row : candidates) {
            String source = row.optString("source");
            Json.put(sources, source, sources.optInt(source) + 1);
        }
        JSONObject settings = db.state("settings", new JSONObject());
        Activity host = null;
        List<ImageView> photos = new ArrayList<>();
        AtomicInteger loaded = new AtomicInteger(), rejected = new AtomicInteger(), pending = new AtomicInteger();
        AtomicInteger largest = new AtomicInteger();
        try {
            JSONObject quiet = Json.copy(settings);
            Json.put(quiet, "background", false);
            db.saveState("settings", quiet);
            ((DomTrackApp) context.getApplicationContext()).schedule();
            UiDevice device = UiDevice.getInstance(instrumentation);
            device.wakeUp();
            host = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            Activity activity = host;
            instrumentation.runOnMainSync(() -> {
                // Detach any feed photos and empty only the RAM LRU, so this check requires new public downloads.
                activity.setContentView(new LinearLayout(activity));
                try {
                    Field memory = RemotePhotos.class.getDeclaredField("MEMORY");
                    memory.setAccessible(true);
                    ((android.util.LruCache<?, ?>) memory.get(null)).evictAll();
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                LinearLayout content = new LinearLayout(activity);
                content.setOrientation(LinearLayout.VERTICAL);
                content.setBackgroundColor(Color.WHITE);
                float density = activity.getResources().getDisplayMetrics().density;
                int padding = Math.round(12 * density);
                content.setPadding(padding, padding, padding, padding);
                TextView title = new TextView(activity);
                title.setText("Фото из исходных объявлений");
                title.setTextSize(20);
                title.setTextColor(Color.rgb(25, 92, 197));
                content.addView(title);
                int availableDp = (int) (activity.getResources().getDisplayMetrics().heightPixels / density) - 160;
                int photoHeightDp = Math.max(70, Math.min(150, availableDp / candidates.size() - 30));
                for (JSONObject row : candidates) {
                    TextView label = new TextView(activity);
                    label.setText("Источник: " + row.optString("source"));
                    label.setPadding(0, Math.round(8 * density), 0, Math.round(4 * density));
                    content.addView(label);
                    RemotePhotos.addThumbnail(content, row, photoHeightDp);
                    View child = content.getChildAt(content.getChildCount() - 1);
                    assertTrue("Valid photo metadata must create a real native ImageView", child instanceof ImageView);
                    photos.add((ImageView) child);
                }
                activity.setContentView(content);
                activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
            instrumentation.waitForIdleSync();
            long deadline = android.os.SystemClock.elapsedRealtime() + 45000;
            do {
                instrumentation.runOnMainSync(() -> {
                    loaded.set(0);
                    rejected.set(0);
                    pending.set(0);
                    for (ImageView photo : photos) {
                        if (photo.getDrawable() instanceof BitmapDrawable drawable && drawable.getBitmap() != null) {
                            Bitmap bitmap = drawable.getBitmap();
                            assertTrue(bitmap.getWidth() > 0 && bitmap.getHeight() > 0);
                            int side = Math.max(bitmap.getWidth(), bitmap.getHeight());
                            assertTrue("The real network pipeline must still downsample photos", side <= 1280);
                            largest.set(Math.max(largest.get(), side));
                            loaded.incrementAndGet();
                        } else if (photo.getVisibility() == View.GONE) rejected.incrementAndGet();
                        else pending.incrementAndGet();
                    }
                });
                if (pending.get() == 0) break;
                Thread.sleep(250);
            } while (android.os.SystemClock.elapsedRealtime() < deadline);
            db.saveState("sourcePhotosDeviceTest", Json.object("candidates", candidates.size(), "sources", sources,
                "loaded", loaded.get(), "unavailable", rejected.get(), "pending", pending.get(),
                "largestDecodedSide", largest.get(), "screenshot", "source-photos.png"));
            assertTrue(device.takeScreenshot(new File(context.getFilesDir(), "source-photos.png")));
            assertTrue("At least one real public source photo must render: candidates=" + candidates.size()
                + ", sources=" + sources + ", loaded=" + loaded.get() + ", unavailable=" + rejected.get()
                + ", pending=" + pending.get(), loaded.get() > 0);
        } finally {
            Activity activity = host;
            if (activity != null) instrumentation.runOnMainSync(activity::finish);
            db.saveState("settings", settings);
            ((DomTrackApp) context.getApplicationContext()).schedule();
        }
    }
}

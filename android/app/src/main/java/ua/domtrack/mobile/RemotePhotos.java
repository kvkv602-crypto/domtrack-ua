package ua.domtrack.mobile;

import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import org.json.JSONObject;

/** Visible source photos only: bounded downloads, downsampled bitmaps and RAM cache, never files. */
final class RemotePhotos {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private static final ThreadPoolExecutor LOADERS = new ThreadPoolExecutor(2, 2, 20,
        TimeUnit.SECONDS, new ArrayBlockingQueue<>(24), runnable -> {
            Thread thread = new Thread(runnable, "DomTrack-photo");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
        .cache(null).cookieJar(CookieJar.NO_COOKIES)
        .dns(host -> {
            List<InetAddress> addresses = Arrays.asList(InetAddress.getAllByName(host));
            for (InetAddress address : addresses) if (!Network.isPublic(address))
                throw new UnknownHostException("Фото доступно только с публичного сайта");
            return addresses;
        })
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();

    static void addThumbnail(LinearLayout parent, JSONObject row) { addThumbnail(parent, row, 190); }

    static void addThumbnail(LinearLayout parent, JSONObject row, int heightDp) {
        List<String> urls = ListingPhotos.urls(row);
        if (urls.isEmpty()) return;
        parent.addView(new PhotoView(parent.getContext(), urls.get(0), null),
            new LinearLayout.LayoutParams(-1, dp(parent.getContext(), heightDp)));
    }

    static void addGallery(LinearLayout parent, JSONObject row) {
        List<String> urls = ListingPhotos.urls(row);
        if (urls.isEmpty()) {
            parent.addView(caption(parent.getContext(), "Фото доступны в исходном объявлении"),
                new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        Context context = parent.getContext();
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(urls.size() > 1);
        LinearLayout strip = new LinearLayout(context);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        TextView error = caption(context, "Часть фото недоступна · откройте исходное объявление");
        error.setVisibility(View.GONE);
        Runnable failed = () -> {
            error.setVisibility(View.VISIBLE);
            boolean any = false;
            for (int i = 0; i < strip.getChildCount(); i++)
                if (strip.getChildAt(i).getVisibility() != View.GONE) { any = true; break; }
            if (!any) scroll.setVisibility(View.GONE);
        };
        int width = Math.max(dp(context, 180), context.getResources().getDisplayMetrics().widthPixels - dp(context, 48));
        for (String url : urls) {
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(width, dp(context, 240));
            layout.setMargins(0, 0, dp(context, 8), 0);
            strip.addView(new PhotoView(context, url, failed), layout);
        }
        scroll.addView(strip, new HorizontalScrollView.LayoutParams(-2, -2));
        parent.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        parent.addView(error, new LinearLayout.LayoutParams(-1, -2));
    }

    private static TextView caption(Context context, String message) {
        TextView text = new TextView(context);
        text.setText(message);
        text.setTextSize(13);
        text.setTextColor(Color.rgb(91, 107, 126));
        text.setPadding(0, dp(context, 8), 0, dp(context, 8));
        return text;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static Bitmap decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > ListingPhotos.MAX_BYTES)
            throw new IOException("Фото отсутствует или превышает лимит размера");
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = ListingPhotos.sampleSize(bounds.outWidth, bounds.outHeight);
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) throw new IOException("Формат фото не поддерживается");
        if (Math.max(bitmap.getWidth(), bitmap.getHeight()) > ListingPhotos.MAX_SIDE) {
            bitmap.recycle();
            throw new IOException("Фото превышает лимит размера");
        }
        return bitmap;
    }

    private static Bitmap download(PhotoView view, int generation) throws IOException {
        HttpUrl url = Network.safe(view.url);
        for (int redirect = 0; redirect < 4; redirect++) {
            Call call = CLIENT.newCall(new Request.Builder().url(url)
                .header("User-Agent", Network.AGENT).header("Accept", "image/jpeg,image/png,image/webp,image/avif")
                .header("Cache-Control", "no-store").build());
            view.activeCall = call;
            if (generation != view.generation) { call.cancel(); throw new IOException("Загрузка фото остановлена"); }
            try (Response response = call.execute()) {
                if (response.isRedirect()) {
                    HttpUrl next = url.resolve(Objects.toString(response.header("Location"), ""));
                    if (next == null || !next.host().equals(url.host()) ||
                            ListingPhotos.safeUrl(next.toString(), "").isEmpty())
                        throw new IOException("Источник фото перенаправил на другой сайт");
                    url = Network.safe(next.toString());
                    continue;
                }
                if (!response.isSuccessful() || response.body() == null) throw new IOException("Фото недоступно");
                String type = response.header("Content-Type", "").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                if (!List.of("image/jpeg", "image/png", "image/webp", "image/avif").contains(type))
                    throw new IOException("Источник не передал поддерживаемое фото");
                try (InputStream stream = response.body().byteStream()) {
                    return decode(ListingPhotos.read(stream, response.body().contentLength()));
                }
            } finally { if (view.activeCall == call) view.activeCall = null; }
        }
        throw new IOException("Источник фото не ответил");
    }

    private static final class PhotoView extends ImageView implements ViewTreeObserver.OnPreDrawListener {
        final String url;
        final Runnable failed;
        final Rect visible = new Rect();
        volatile int generation;
        volatile Call activeCall;
        Future<?> task;
        boolean displayed, rejected;

        PhotoView(Context context, String url, Runnable failed) {
            super(context);
            this.url = url;
            this.failed = failed;
            setScaleType(ImageView.ScaleType.CENTER_CROP);
            setBackgroundColor(Color.rgb(233, 239, 246));
            setContentDescription("Фотография объекта с исходного сайта");
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            getViewTreeObserver().addOnPreDrawListener(this);
        }

        @Override protected void onDetachedFromWindow() {
            if (getViewTreeObserver().isAlive()) getViewTreeObserver().removeOnPreDrawListener(this);
            clear();
            super.onDetachedFromWindow();
        }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility != View.VISIBLE) clear();
        }

        @Override public void onVisibilityAggregated(boolean isVisible) {
            super.onVisibilityAggregated(isVisible);
            if (!isVisible) clear();
        }

        private boolean visibleNow() {
            return isAttachedToWindow() && isShown() && getWindowVisibility() == View.VISIBLE &&
                getGlobalVisibleRect(visible) && !visible.isEmpty();
        }

        @Override public boolean onPreDraw() {
            if (visibleNow()) {
                if (!displayed && task == null && !rejected) load();
            } else if (displayed || task != null) clear();
            return true;
        }

        private void clear() {
            generation++;
            if (activeCall != null) activeCall.cancel();
            if (task != null) {
                task.cancel(true);
                if (task instanceof Runnable pending) LOADERS.remove(pending);
                task = null;
            }
            setImageDrawable(null);
            displayed = false;
        }

        private void load() {
            Bitmap cached = MEMORY.get(url);
            if (cached != null) { displayed = true; setImageBitmap(cached); return; }
            int requested = ++generation;
            try {
                task = LOADERS.submit(() -> {
                    Bitmap result = null;
                    try { result = download(this, requested); } catch (Exception | OutOfMemoryError ignored) {}
                    if (requested != generation) { if (result != null) result.recycle(); return; }
                    if (result != null) MEMORY.put(url, result);
                    Bitmap bitmap = result;
                    MAIN.post(() -> {
                        if (requested != generation) return;
                        task = null;
                        if (!visibleNow()) return;
                        if (bitmap == null) unavailable();
                        else { displayed = true; setImageBitmap(bitmap); }
                    });
                });
            } catch (RejectedExecutionException ignored) { unavailable(); }
        }

        private void unavailable() {
            rejected = true;
            setImageDrawable(null);
            setVisibility(View.GONE);
            if (failed != null) failed.run();
        }
    }
}

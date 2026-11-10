package ua.domtrack.mobile;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.text.*;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.view.*;
import android.widget.*;
import java.util.List;
import org.json.JSONObject;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.*;

/** A source-bound native map preview whose renderer exists only while its page area is visible. */
final class ListingMapPreview {
    private static final int BLUE = 0xff195cc5, PALE = 0xffedf4ff, MUTED = 0xff64748b;

    static void add(LinearLayout parent, JSONObject row, Runnable openFullMap) {
        Context context = parent.getContext();
        if (!MapPresentation.hasCoordinates(row)) {
            parent.addView(caption(context, "Источник не передал координаты объекта."),
                new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        Preview preview = new Preview(context, new LatLng(row.optDouble("latitude"), row.optDouble("longitude")),
            MapPresentation.precision(row) == MapPresentation.Precision.APPROXIMATE ? 13 : 16, openFullMap);
        LinearLayout.LayoutParams mapLayout = new LinearLayout.LayoutParams(-1, dp(context, 220));
        mapLayout.setMargins(0, dp(context, 8), 0, 0);
        parent.addView(preview, mapLayout);
        parent.addView(caption(context, MapPresentation.precision(row) == MapPresentation.Precision.APPROXIMATE
            ? "Приблизительная точка источника · положение дома не подтверждено"
            : "Точка источника · точность положения дома не подтверждена"),
            new LinearLayout.LayoutParams(-1, -2));
        TextView attribution = caption(context, "© OpenFreeMap · © OpenMapTiles · © OpenStreetMap contributors");
        SpannableString links = new SpannableString(attribution.getText());
        for (String[] link : new String[][] {
                { "OpenFreeMap", "https://openfreemap.org" },
                { "OpenMapTiles", "https://openmaptiles.org" },
                { "OpenStreetMap contributors", "https://www.openstreetmap.org/copyright" } }) {
            int start = links.toString().indexOf(link[0]);
            links.setSpan(new URLSpan(link[1]), start, start + link[0].length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        attribution.setText(links);
        attribution.setTextSize(11);
        attribution.setLinkTextColor(BLUE);
        attribution.setMovementMethod(LinkMovementMethod.getInstance());
        parent.addView(attribution, new LinearLayout.LayoutParams(-1, -2));
    }

    private static TextView caption(Context context, String value) {
        TextView caption = new TextView(context);
        caption.setText(value);
        caption.setTextColor(MUTED);
        caption.setTextSize(12);
        caption.setPadding(0, dp(context, 5), 0, dp(context, 3));
        return caption;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class Preview extends FrameLayout implements ViewTreeObserver.OnPreDrawListener {
        private final LatLng sourcePoint;
        private final double cameraZoom;
        private final Rect visible = new Rect();
        private final PointOverlay point;
        private final TextView status;
        private final Runnable create;
        private MapView mapView;
        private MapLibreMap map;
        private boolean queued, loaded;
        private int generation;
        private String style = "bright";

        Preview(Context context, LatLng sourcePoint, double cameraZoom, Runnable openFullMap) {
            super(context);
            this.sourcePoint = sourcePoint;
            this.cameraZoom = cameraZoom;
            setContentDescription("Карта расположения объекта");
            setBackgroundColor(PALE);
            status = caption(context, "Карта расположения объекта");
            status.setGravity(Gravity.CENTER);
            addView(status, new FrameLayout.LayoutParams(-1, -1));
            point = new PointOverlay(context);
            point.setContentDescription("Точка объекта на карте · открыть полную карту");
            point.setOnClickListener(v -> openFullMap.run());
            addView(point, new FrameLayout.LayoutParams(-1, -1));
            Button expand = new Button(context);
            expand.setText("Развернуть карту");
            expand.setAllCaps(false);
            expand.setTextColor(BLUE);
            expand.setTextSize(12);
            expand.setMinHeight(dp(context, 40));
            expand.setMinimumHeight(dp(context, 40));
            expand.setPadding(dp(context, 12), dp(context, 5), dp(context, 12), dp(context, 5));
            expand.setStateListAnimator(null);
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.WHITE);
            background.setCornerRadius(dp(context, 10));
            expand.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22195cc5), background, null));
            expand.setElevation(dp(context, 3));
            expand.setOnClickListener(v -> openFullMap.run());
            FrameLayout.LayoutParams expandLayout = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM);
            expandLayout.setMargins(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10));
            addView(expand, expandLayout);
            create = () -> {
                queued = false;
                if (visibleNow() && mapView == null) createNative();
            };
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            getViewTreeObserver().addOnPreDrawListener(this);
        }

        @Override protected void onDetachedFromWindow() {
            if (getViewTreeObserver().isAlive()) getViewTreeObserver().removeOnPreDrawListener(this);
            destroyNative();
            super.onDetachedFromWindow();
        }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility != View.VISIBLE) destroyNative();
        }

        @Override public void onVisibilityAggregated(boolean shown) {
            super.onVisibilityAggregated(shown);
            if (!shown) destroyNative();
        }

        private boolean visibleNow() {
            return isAttachedToWindow() && isShown() && getWindowVisibility() == View.VISIBLE &&
                getWidth() > 0 && getHeight() > 0 && getGlobalVisibleRect(visible) && !visible.isEmpty();
        }

        @Override public boolean onPreDraw() {
            if (!visibleNow()) destroyNative();
            else if (mapView == null && !queued) {
                queued = true;
                // Let the details and their text appear before initializing a native renderer.
                post(create);
            }
            return true;
        }

        private void createNative() {
            MapLibre.getInstance(getContext());
            JSONObject preferences = ((DomTrackApp) getContext().getApplicationContext()).db()
                .state("mapUi", new JSONObject());
            String requestedStyle = preferences.optString("style", "bright");
            style = List.of("bright", "positron", "liberty").contains(requestedStyle) ? requestedStyle : "bright";
            CameraPosition camera = new CameraPosition.Builder().target(sourcePoint).zoom(cameraZoom)
                .tilt(cameraZoom < 14.5 ? 0 : 45).build();
            MapLibreMapOptions options = MapLibreMapOptions.createFromAttributes(getContext())
                .textureMode(true).camera(camera).compassEnabled(false).logoEnabled(false).attributionEnabled(false)
                .scrollGesturesEnabled(false).zoomGesturesEnabled(false).rotateGesturesEnabled(false)
                .tiltGesturesEnabled(false).doubleTapGesturesEnabled(false).quickZoomGesturesEnabled(false);
            MapView nativeView = new MapView(getContext(), options);
            int request = ++generation;
            mapView = nativeView;
            loaded = false;
            status.setText("Загрузка карты…");
            status.setVisibility(View.VISIBLE);
            nativeView.onCreate(null);
            addView(nativeView, 0, new FrameLayout.LayoutParams(-1, -1));
            nativeView.onStart();
            nativeView.onResume();
            nativeView.addOnDidFailLoadingMapListener(error -> {
                if (request != generation || mapView != nativeView) return;
                status.setText("Карта недоступна · открой полную карту");
            });
            nativeView.getMapAsync(ready -> {
                if (request != generation || mapView != nativeView) return;
                map = ready;
                ready.getUiSettings().setAllGesturesEnabled(false);
                ready.setCameraPosition(camera);
                ready.addOnCameraMoveListener(point::invalidate);
                ready.setStyle("https://tiles.openfreemap.org/styles/" + style, nativeStyle -> {
                    if (request != generation || mapView != nativeView) return;
                    MapBuildings.apply(nativeStyle);
                    loaded = true;
                    status.setVisibility(View.GONE);
                    point.invalidate();
                });
            });
        }

        private void destroyNative() {
            if (create != null) removeCallbacks(create);
            queued = false;
            MapView previous = mapView;
            if (previous == null) return;
            generation++;
            mapView = null;
            map = null;
            loaded = false;
            previous.onPause();
            previous.onStop();
            previous.onDestroy();
            removeView(previous);
            status.setText("Карта расположения объекта");
            status.setVisibility(View.VISIBLE);
            point.invalidate();
        }

        /** Covers the native gesture surface so the enclosing ScrollView keeps ordinary vertical scrolling. */
        private final class PointOverlay extends View {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            PointOverlay(Context context) { super(context); }
            @Override protected void onDraw(Canvas canvas) {
                if (!loaded || map == null) return;
                PointF position = map.getProjection().toScreenLocation(sourcePoint);
                if (!Float.isFinite(position.x) || !Float.isFinite(position.y)) return;
                paint.setColor(0x33195cc5);
                canvas.drawCircle(position.x, position.y, dp(getContext(), 20), paint);
                paint.setColor(Color.WHITE);
                canvas.drawCircle(position.x, position.y, dp(getContext(), 11), paint);
                paint.setColor(BLUE);
                canvas.drawCircle(position.x, position.y, dp(getContext(), 7), paint);
            }
        }
    }
}

package ua.domtrack.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.*;

/** Native vector map with a touch drawing layer; no JavaScript or embedded web page. */
public class MapActivity extends Activity {

    private MapView view;
    private MapLibreMap map;
    private Drawing drawing;
    private JSONObject filters;
    private JSONObject mapQuote;
    private final Map<String, String> labels = new HashMap<>();
    private TextView hint, coverage;
    private final android.os.Handler refreshHandler = new android.os.Handler(
        android.os.Looper.getMainLooper()
    );
    private final java.util.concurrent.ExecutorService markerIo =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean active;
    private final Runnable refresh = new Runnable() {
        public void run() {
            if (!active) return;
            markerIo.execute(() -> {
                Database db = ((DomTrackApp) getApplication()).db();
                JSONObject quote = db.state("fx:" + Model.date(), new JSONObject()),
                    f = Json.copy(filters);

                ArrayList<JSONObject> rows = new ArrayList<>();
                int missing = 0;
                for (JSONObject r : db.listings())
                    if (!r.optBoolean("hidden") && Model.matches(r, f, quote)) {
                        if (
                            r.has("latitude") &&
                            r.has("longitude") &&
                            Double.isFinite(r.optDouble("latitude")) &&
                            Double.isFinite(r.optDouble("longitude"))
                        ) rows.add(r);
                        else missing++;
                    }
                final int without = missing;
                runOnUiThread(() -> {
                    if (!active) return;
                    mapQuote = quote;
                    listings.clear();
                    listings.addAll(rows);
                    labels.clear();
                    for (JSONObject r : listings) labels.put(r.optString("id"), price(r));
                    if (coverage != null) coverage.setText(
                        "Координаты площадок: " +
                            rows.size() +
                            " · без координат: " +
                            without +
                            "\n© OpenFreeMap · OpenMapTiles · OpenStreetMap"
                    );
                    drawing.invalidate();
                    refreshHandler.postDelayed(this, 7000);
                });
            });
        }
    };
    private final ArrayList<LatLng> zone = new ArrayList<>();
    private final ArrayList<JSONObject> listings = new ArrayList<>();
    private final ArrayDeque<ArrayList<LatLng>> undo = new ArrayDeque<>();
    private String mode = "Двигать";
    private boolean loaded;

    public void onCreate(Bundle state) {
        super.onCreate(state);
        MapLibre.getInstance(this);
        filters = Json.obj(
            state == null ? getIntent().getStringExtra("filters") : state.getString("filters")
        );
        JSONArray polygon = state == null
            ? filters.optJSONArray("polygon")
            : Json.obj(state.getString("shape", "{}")).optJSONArray("polygon");
        if (polygon != null) for (int i = 0; i < polygon.length(); i++) {
            JSONArray point = polygon.optJSONArray(i);
            if (point != null) zone.add(new LatLng(point.optDouble(0), point.optDouble(1)));
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
            );
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        TextView title = new TextView(this);
        title.setText("Территория поиска · " + filters.optString("city"));
        title.setTextSize(20);
        title.setPadding(dp(12), dp(8), dp(12), dp(4));
        root.addView(title);
        hint = new TextView(this);
        hint.setPadding(dp(12), 0, dp(12), dp(6));
        root.addView(hint);
        HorizontalScrollView toolbar = new HorizontalScrollView(this);
        LinearLayout modes = new LinearLayout(this);
        for (String label : List.of("Двигать", "Пальцем", "Точки", "Прямоугольник", "Править"))
            modes.addView(
                button(label, () -> {
                    mode = label;
                    updateHint();
                    drawing.invalidate();
                })
            );
        toolbar.addView(modes);
        root.addView(toolbar);
        root.addView(
            button("Загрузить координаты из объявлений", () -> {
                if (SearchService.RUNNING.get()) {
                    Toast.makeText(
                        this,
                        "Дождись завершения поиска или останови его через уведомление",
                        Toast.LENGTH_LONG
                    ).show();
                    return;
                }
                try {
                    SearchService.coordinates(this, Json.copy(filters));
                    Toast.makeText(
                        this,
                        "Координаты загружаются; карта обновится автоматически",
                        Toast.LENGTH_LONG
                    ).show();
                } catch (Exception e) {
                    Toast.makeText(this, Scanner.safe(e), Toast.LENGTH_LONG).show();
                }
            })
        );
        FrameLayout frame = new FrameLayout(this);
        view = new MapView(this);
        view.onCreate(state);
        frame.addView(view, new FrameLayout.LayoutParams(-1, -1));
        drawing = new Drawing();
        frame.addView(drawing, new FrameLayout.LayoutParams(-1, -1));
        root.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bottom = new LinearLayout(this);
        bottom.addView(
            button("Отменить", () -> {
                if (!undo.isEmpty()) {
                    zone.clear();
                    zone.addAll(undo.pop());
                    drawing.invalidate();
                    updateHint();
                }
            }),
            new LinearLayout.LayoutParams(0, -2, 1)
        );
        bottom.addView(
            button("Очистить", () -> {
                snapshot();
                zone.clear();
                drawing.invalidate();
                updateHint();
            }),
            new LinearLayout.LayoutParams(0, -2, 1)
        );
        bottom.addView(button("Применить", this::apply), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(bottom);
        setContentView(root);
        Database db = ((DomTrackApp) getApplication()).db();
        mapQuote = db.state("fx:" + Model.date(), new JSONObject());
        JSONObject noZone = Json.copy(filters);

        int missing = 0;
        for (JSONObject r : db.listings())
            if (!r.optBoolean("hidden") && Model.matches(r, noZone, mapQuote)) {
                if (
                    r.has("latitude") &&
                    r.has("longitude") &&
                    Double.isFinite(r.optDouble("latitude")) &&
                    Double.isFinite(r.optDouble("longitude"))
                ) listings.add(r);
                else missing++;
            }
        for (JSONObject r : listings) labels.put(r.optString("id"), price(r));
        final int without = missing;
        view.getMapAsync(m -> {
            map = m;
            map.getUiSettings().setRotateGesturesEnabled(false);
            map.getUiSettings().setTiltGesturesEnabled(false);
            map.addOnCameraMoveListener(() -> drawing.invalidate());
            LatLng center = zone.isEmpty() ? cityCenter(filters.optString("city")) : zone.get(0);
            map.setCameraPosition(new CameraPosition.Builder().target(center).zoom(12).build());
            map.setStyle("https://tiles.openfreemap.org/styles/liberty", style -> {
                loaded = true;
                updateHint();
                drawing.invalidate();
            });
            view.addOnDidFailLoadingMapListener(error ->
                hint.setText("Карта не загрузилась. Проверь интернет и открой карту снова.")
            );
            updateHint();
            TextView attribution = new TextView(this);
            attribution.setText(
                "Координаты площадок: " +
                    listings.size() +
                    " · без координат: " +
                    without +
                    "\n© OpenFreeMap · OpenMapTiles · OpenStreetMap"
            );
            attribution.setTextSize(11);
            root.addView(attribution);
        });
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private void snapshot() {
        undo.push(new ArrayList<>(zone));
        if (undo.size() > 30) undo.removeLast();
    }

    private void updateHint() {
        String text = switch (mode) {
            case "Пальцем" -> "Обведи область пальцем одним движением";
            case "Точки" -> "Нажимай на границу участка: минимум три точки";
            case "Прямоугольник" -> "Проведи от одного угла к противоположному";
            case "Править" -> "Перетаскивай точки границы";
            default -> "Двигай карту и меняй масштаб двумя пальцами";
        };
        hint.setText((loaded ? "" : "Загрузка карты… · ") + text + " · точек: " + zone.size());
    }

    private JSONArray polygon() {
        JSONArray out = new JSONArray();
        for (LatLng p : zone)
            out.put(new JSONArray(java.util.List.of(p.getLatitude(), p.getLongitude())));
        return out;
    }

    private void apply() {
        if (!zone.isEmpty() && (zone.size() < 3 || !Geometry.valid(polygon()))) {
            Toast.makeText(
                this,
                "Нужна замкнутая область без пересечений: минимум 3 точки",
                Toast.LENGTH_LONG
            ).show();
            return;
        }
        if (zone.isEmpty()) filters.remove("polygon");
        else Json.put(filters, "polygon", polygon());
        setResult(RESULT_OK, new Intent().putExtra("filters", filters.toString()));
        finish();
    }

    private static LatLng cityCenter(String city) {
        double[][] centers = {
            { 50.4501, 30.5234 },
            { 46.4825, 30.7233 },
            { 49.9935, 36.2304 },
            { 49.8397, 24.0297 },
            { 48.4647, 35.0462 },
            { 48.9226, 24.7111 },
            { 49.4229, 26.9871 },
            { 49.2331, 28.4682 },
            { 50.6199, 26.2516 },
            { 48.6208, 22.2879 },
            { 47.8388, 35.1396 },
            { 49.5883, 34.5514 },
            { 49.5535, 25.5948 },
            { 50.9077, 34.7981 },
            { 51.4982, 31.2893 },
            { 46.9750, 31.9946 },
            { 48.2921, 25.9358 },
            { 50.2547, 28.6587 },
            { 49.4444, 32.0598 },
            { 48.5079, 32.2623 },
            { 50.7472, 25.3254 },
            { 46.6354, 32.6169 },
            { 50.5218, 30.2506 },
            { 50.5430, 30.2120 },
        };
        for (int i = 0; i < Sources.CITIES.length; i++) if (
            Bilingual.city(city).equals(Bilingual.city(Sources.CITIES[i][0]))
        ) return new LatLng(centers[i][0], centers[i][1]);
        return new LatLng(49, 32);
    }

    private String price(JSONObject record) {
        String currency = filters.optString("currency", "EUR");
        double amount = Model.convert(
            record.optDouble("price"),
            record.optString("currency"),
            currency,
            mapQuote
        );
        if (!Double.isFinite(amount)) {
            amount = record.optDouble("price");
            currency = record.optString("currency");
        }
        return String.format(Locale.forLanguageTag("ru"), "%,.0f %s", amount, currency);
    }

    private void openMarker(JSONObject record) {
        new AlertDialog.Builder(this)
            .setTitle(price(record))
            .setMessage(
                record.optString("title") +
                    "\n" +
                    record.optString("address") +
                    "\n" +
                    record.optString("source")
            )
            .setPositiveButton("Карточка объявления", (d, w) -> {
                setResult(
                    RESULT_OK,
                    new Intent()
                        .putExtra("filters", filters.toString())
                        .putExtra("listingId", record.optString("id"))
                );
                finish();
            })
            .setNegativeButton("К карте", null)
            .show();
    }

    private class Drawing extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private PointF first, previous;
        private int vertex = -1;
        private final ArrayList<RectF> hitBoxes = new ArrayList<>();
        private final ArrayList<JSONObject> hitRecords = new ArrayList<>();
        private JSONObject pressedMarker;

        Drawing() {
            super(MapActivity.this);
            setContentDescription("Граница территории поиска");
        }

        protected void onDraw(Canvas canvas) {
            if (map == null) return;
            hitBoxes.clear();
            hitRecords.clear();
            paint.setTextSize(dp(12));
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            ArrayList<RectF> occupied = new ArrayList<>();
            for (JSONObject r : listings) {
                PointF p = map
                    .getProjection()
                    .toScreenLocation(
                        new LatLng(r.optDouble("latitude"), r.optDouble("longitude"))
                    );
                if (p.x < 0 || p.x > getWidth() || p.y < 0 || p.y > getHeight()) continue;
                String label = labels.getOrDefault(r.optString("id"), r.optString("currency"));
                float width = paint.measureText(label) + dp(14);
                float left = Math.max(0, Math.min(getWidth() - width, p.x - width / 2));
                RectF box = new RectF(left, p.y - dp(29), left + width, p.y - dp(5));
                for (int attempt = 0; attempt < 8; attempt++) {
                    boolean collision = false;
                    for (RectF previous : occupied)
                        if (RectF.intersects(box, previous)) {
                            collision = true;
                            break;
                        }
                    if (!collision) break;
                    box.offset(0, -dp(26));
                }
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1));
                paint.setColor(0xff167e68);
                canvas.drawLine(p.x, p.y, box.centerX(), box.bottom, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(p.x, p.y, dp(3), paint);
                canvas.drawRoundRect(box, dp(7), dp(7), paint);
                paint.setColor(Color.WHITE);
                canvas.drawText(label, box.left + dp(7), box.top + dp(16), paint);
                occupied.add(box);
                hitBoxes.add(box);
                hitRecords.add(r);
            }
            if (zone.isEmpty()) return;
            Path path = new Path();
            ArrayList<PointF> points = new ArrayList<>();
            for (LatLng p : zone) points.add(map.getProjection().toScreenLocation(p));
            path.moveTo(points.get(0).x, points.get(0).y);
            for (int i = 1; i < points.size(); i++) path.lineTo(points.get(i).x, points.get(i).y);
            if (points.size() >= 3) path.close();
            paint.setColor(0x33167e68);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0xff167e68);
            paint.setStrokeWidth(dp(3));
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            for (PointF p : points)
                canvas.drawCircle(p.x, p.y, dp(mode.equals("Править") ? 7 : 4), paint);
        }

        public boolean onTouchEvent(MotionEvent e) {
            if (map == null || !loaded) return false;
            if (mode.equals("Двигать")) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    pressedMarker = null;
                    for (int i = hitBoxes.size() - 1; i >= 0; i--) if (
                        hitBoxes.get(i).contains(e.getX(), e.getY())
                    ) {
                        pressedMarker = hitRecords.get(i);
                        break;
                    }
                    return pressedMarker != null;
                }
                if (pressedMarker != null) {
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                        performClick();
                        openMarker(pressedMarker);
                        pressedMarker = null;
                    } else if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) pressedMarker =
                        null;
                    return true;
                }
                return false;
            }
            PointF point = new PointF(e.getX(), e.getY());
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                first = previous = point;
                vertex = -1;
                snapshot();
                if (mode.equals("Править")) {
                    for (int i = 0; i < zone.size(); i++) {
                        PointF p = map.getProjection().toScreenLocation(zone.get(i));
                        if (Math.hypot(p.x - point.x, p.y - point.y) < dp(28)) {
                            vertex = i;
                            break;
                        }
                    }
                } else if (mode.equals("Пальцем") || mode.equals("Прямоугольник")) {
                    zone.clear();
                    zone.add(map.getProjection().fromScreenLocation(point));
                } else if (mode.equals("Точки")) zone.add(
                    map.getProjection().fromScreenLocation(point)
                );
            } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                if (mode.equals("Править") && vertex >= 0) zone.set(
                    vertex,
                    map.getProjection().fromScreenLocation(point)
                );
                if (
                    mode.equals("Пальцем") &&
                    zone.size() < 500 &&
                    Math.hypot(point.x - previous.x, point.y - previous.y) > dp(12)
                ) {
                    zone.add(map.getProjection().fromScreenLocation(point));
                    previous = point;
                }
                if (mode.equals("Прямоугольник") && first != null) {
                    zone.clear();
                    for (PointF p : List.of(
                        first,
                        new PointF(point.x, first.y),
                        point,
                        new PointF(first.x, point.y)
                    ))
                        zone.add(map.getProjection().fromScreenLocation(p));
                }
            } else if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                if (!undo.isEmpty()) {
                    zone.clear();
                    zone.addAll(undo.pop());
                }
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                performClick();
                if (mode.equals("Пальцем") && zone.size() > 3) {
                    JSONArray simplified = Geometry.simplify(polygon(), .00004);
                    zone.clear();
                    for (int i = 0; i < simplified.length(); i++) {
                        JSONArray p = simplified.optJSONArray(i);
                        zone.add(new LatLng(p.optDouble(0), p.optDouble(1)));
                    }
                }
            }
            invalidate();
            updateHint();
            return true;
        }

        public boolean performClick() {
            super.performClick();
            return true;
        }
    }

    protected void onStart() {
        super.onStart();
        view.onStart();
        active = true;
        refreshHandler.postDelayed(refresh, 7000);
    }

    protected void onResume() {
        super.onResume();
        view.onResume();
    }

    protected void onPause() {
        view.onPause();
        super.onPause();
    }

    protected void onStop() {
        active = false;
        refreshHandler.removeCallbacks(refresh);
        view.onStop();
        super.onStop();
    }

    protected void onDestroy() {
        markerIo.shutdownNow();
        view.onDestroy();
        super.onDestroy();
    }

    public void onLowMemory() {
        super.onLowMemory();
        view.onLowMemory();
    }

    protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        view.onSaveInstanceState(b);
        b.putString("filters", filters.toString());
        b.putString("shape", Json.object("polygon", polygon()).toString());
    }
}

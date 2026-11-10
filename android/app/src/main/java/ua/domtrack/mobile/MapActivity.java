package ua.domtrack.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.*;

/** Native vector map with a touch drawing layer; no JavaScript or embedded web page. */
public class MapActivity extends Activity {

    private static final int BLUE = 0xff195cc5, INK = 0xff162b4d, MUTED = 0xff64748b,
        PALE_BLUE = 0xffedf4ff, LINE = 0xffdce5f2;

    private MapView view;
    private MapLibreMap map;
    private Drawing drawing;
    private JSONObject filters;
    private JSONObject mapQuote;
    private MapPriceGroups.Pricing markerPricing;
    private String selectedListingId = "";
    private JSONObject selectedRecord;
    private TextView selectedPrice, selectedAddress, selectedSource, selectedQuality;
    private Button routeButton, recordButton;
    private Button mapStyleButton;
    private Button perspectiveButton;
    private boolean perspective3d = true;
    private String mapStyle = "bright";
    private int styleRequest;
    private final Map<String, Button> modeButtons = new LinkedHashMap<>();
    private final Map<String, String> labels = new HashMap<>();
    private TextView hint, coverage;
    private final android.os.Handler refreshHandler = new android.os.Handler(
        android.os.Looper.getMainLooper()
    );
    private final java.util.concurrent.ExecutorService markerIo =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean active;
    private boolean globalCameraFramed, userMovedCamera;
    private boolean selectedCameraFramed;
    private final Runnable refresh = new Runnable() {
        public void run() {
            if (!active) return;
            markerIo.execute(() -> {
                Database db = ((DomTrackApp) getApplication()).db();
                JSONObject quote = db.state("fx:" + Model.date(), new JSONObject()),
                    f = MapFilters.candidates(filters);

                ArrayList<JSONObject> rows = new ArrayList<>();
                int missing = 0;
                JSONObject selected = selectedMode() ? db.get(selectedListingId) : null;
                if (selectedMode()) {
                    if (MapPresentation.hasCoordinates(selected)) rows.add(selected);
                    else if (selected != null) missing++;
                } else for (JSONObject r : db.listings())
                    if (!r.optBoolean("hidden") && Model.matches(r, f, quote)) {
                        if (MapPresentation.hasCoordinates(r)) rows.add(r);
                        else missing++;
                    }
                List<JSONObject> unique = selectedMode() ? rows : SemanticIndex.display(
                    rows,
                    quote,
                    f.optString("currency", "USD"),
                    db.state("duplicateIndex", new JSONObject())
                );
                final int without = missing;
                runOnUiThread(() -> {
                    if (!active) return;
                    mapQuote = quote;
                    markerPricing = new MapPriceGroups.Pricing(filters.optString("currency", "EUR"), quote);
                    if (selectedMode()) selectedRecord = selected;
                    listings.clear();
                    listings.addAll(unique);
                    labels.clear();
                    for (JSONObject r : listings) labels.put(r.optString("id"), markerPrice(r));
                    updateSelectedPanel();
                    if (coverage != null) coverage.setText(coverageText(unique.size(), without));
                    frameCamera();
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
        selectedListingId = state == null
            ? Objects.toString(getIntent().getStringExtra("selectedListingId"), "")
            : state.getString("selectedListingId", "");
        Database db = ((DomTrackApp) getApplication()).db();
        String savedMapStyle = db.state("mapUi", new JSONObject()).optString("style", "bright");
        mapStyle = List.of("bright", "positron", "liberty").contains(savedMapStyle) ? savedMapStyle : "bright";
        selectedRecord = selectedMode() ? db.get(selectedListingId) : null;
        if (state != null) {
            globalCameraFramed = state.getBoolean("globalCameraFramed");
            userMovedCamera = state.getBoolean("userMovedCamera");
            selectedCameraFramed = state.getBoolean("selectedCameraFramed");
            perspective3d = state.getBoolean("perspective3d", true);
        }
        JSONArray polygon = selectedMode() ? null : state == null
            ? filters.optJSONArray("polygon")
            : Json.obj(state.getString("shape", "{}")).optJSONArray("polygon");
        if (polygon != null) for (int i = 0; i < polygon.length(); i++) {
            JSONArray point = polygon.optJSONArray(i);
            if (point != null) zone.add(new LatLng(point.optDouble(0), point.optDouble(1)));
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
            );
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(8), dp(16), dp(6));
        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = new TextView(this);
        brand.setText("DomTrack");
        brand.setTextSize(21);
        brand.setTypeface(null, Typeface.BOLD);
        brand.setTextColor(BLUE);
        brandRow.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        mapStyleButton = button("Вид карты", this::showMapStyles);
        mapStyleButton.setTextSize(12);
        mapStyleButton.setMinHeight(dp(32));
        mapStyleButton.setMinimumHeight(dp(32));
        mapStyleButton.setPadding(dp(10), dp(4), dp(10), dp(4));
        mapStyleButton.setContentDescription("Вид карты: " + mapStyle);
        brandRow.addView(mapStyleButton, new LinearLayout.LayoutParams(-2, dp(32)));
        header.addView(brandRow);
        TextView title = new TextView(this);
        title.setText(selectedMode() ? "Объект на карте" : "Территория поиска · " +
            (filters.optString("city").isBlank() ? "Все города" : filters.optString("city")));
        title.setTextSize(13);
        title.setTextColor(MUTED);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(0, dp(2), 0, 0);
        header.addView(title);
        root.addView(header);
        hint = new TextView(this);
        hint.setTextSize(12);
        hint.setTextColor(INK);
        hint.setPadding(dp(10), dp(6), dp(10), dp(6));
        hint.setBackground(surface(PALE_BLUE, 10, 0));
        LinearLayout.LayoutParams hintLayout = new LinearLayout.LayoutParams(-1, -2);
        hintLayout.setMargins(dp(12), dp(2), dp(12), dp(6));
        root.addView(hint, hintLayout);
        HorizontalScrollView toolbar = new HorizontalScrollView(this);
        toolbar.setHorizontalScrollBarEnabled(false);
        LinearLayout modes = new LinearLayout(this);
        modes.setPadding(dp(12), 0, dp(6), dp(6));
        for (String label : List.of("Двигать", "Пальцем", "Точки", "Прямоугольник", "Править")) {
            Button control = button(label, () -> {
                mode = label;
                updateHint();
                updateModeButtons();
                drawing.invalidate();
            });
            control.setTextSize(13);
            control.setMinHeight(dp(38));
            control.setMinimumHeight(dp(38));
            LinearLayout.LayoutParams controlLayout = new LinearLayout.LayoutParams(-2, dp(38));
            controlLayout.setMargins(0, 0, dp(6), 0);
            modes.addView(control, controlLayout);
            modeButtons.put(label, control);
        }
        updateModeButtons();
        toolbar.addView(modes);
        if (!selectedMode()) root.addView(toolbar);
        else {
            LinearLayout selectedControls = new LinearLayout(this);
            selectedControls.setPadding(dp(12), 0, dp(12), dp(6));
            Button recenter = button("К объекту", () -> {
                userMovedCamera = false;
                selectedCameraFramed = false;
                frameCamera();
            });
            LinearLayout.LayoutParams recenterLayout = new LinearLayout.LayoutParams(0, dp(36), 1);
            recenterLayout.setMargins(0, 0, dp(8), 0);
            selectedControls.addView(recenter, recenterLayout);
            perspectiveButton = button("2D / 3D", this::togglePerspective);
            updatePerspectiveButton();
            selectedControls.addView(perspectiveButton, new LinearLayout.LayoutParams(dp(94), dp(36)));
            root.addView(selectedControls);
        }
        if (!selectedMode()) {
            Button coordinates = button("Загрузить координаты из объявлений", () -> {
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
            });
            coordinates.setTextSize(12);
            LinearLayout.LayoutParams coordinatesLayout = new LinearLayout.LayoutParams(-1, dp(36));
            coordinatesLayout.setMargins(dp(12), 0, dp(12), dp(6));
            root.addView(coordinates, coordinatesLayout);
        }
        FrameLayout frame = new FrameLayout(this);
        view = new MapView(this);
        view.onCreate(state);
        frame.addView(view, new FrameLayout.LayoutParams(-1, -1));
        view.addOnLayoutChangeListener((v, left, top, right, bottomEdge, oldLeft, oldTop, oldRight, oldBottom) ->
            frameCamera());
        drawing = new Drawing();
        frame.addView(drawing, new FrameLayout.LayoutParams(-1, -1));
        root.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bottom = new LinearLayout(this);
        bottom.setPadding(dp(12), dp(10), dp(12), dp(10));
        bottom.setBackgroundColor(Color.WHITE);
        bottom.setElevation(dp(6));
        bottom.addView(
            button("Отменить", () -> {
                if (!undo.isEmpty()) {
                    zone.clear();
                    zone.addAll(undo.pop());
                    drawing.invalidate();
                    updateHint();
                }
            }),
            actionLayout(true)
        );
        bottom.addView(
            button("Очистить", () -> {
                snapshot();
                zone.clear();
                drawing.invalidate();
                updateHint();
            }),
            actionLayout(true)
        );
        Button apply = button("Применить", this::apply);
        styleButton(apply, true);
        bottom.addView(apply, actionLayout(false));
        if (!selectedMode()) root.addView(bottom);
        else root.addView(selectedPanel());
        setContentView(root);
        mapQuote = db.state("fx:" + Model.date(), new JSONObject());
        markerPricing = new MapPriceGroups.Pricing(filters.optString("currency", "EUR"), mapQuote);
        JSONObject noZone = MapFilters.candidates(filters);

        int missing = 0;
        if (selectedMode()) {
            if (MapPresentation.hasCoordinates(selectedRecord)) listings.add(selectedRecord);
            else if (selectedRecord != null) missing++;
        } else for (JSONObject r : db.listings())
            if (!r.optBoolean("hidden") && Model.matches(r, noZone, mapQuote)) {
                if (MapPresentation.hasCoordinates(r)) listings.add(r);
                else missing++;
            }
        List<JSONObject> unique = selectedMode() ? new ArrayList<>(listings) : SemanticIndex.display(
            listings,
            mapQuote,
            filters.optString("currency", "USD"),
            db.state("duplicateIndex", new JSONObject())
        );
        listings.clear();
        listings.addAll(unique);
        for (JSONObject r : listings) labels.put(r.optString("id"), markerPrice(r));
        updateSelectedPanel();
        final int without = missing;
        view.getMapAsync(m -> {
            map = m;
            map.getUiSettings().setRotateGesturesEnabled(false);
            map.getUiSettings().setTiltGesturesEnabled(false);
            map.getUiSettings().setAttributionEnabled(true);
            map.addOnCameraMoveListener(() -> drawing.invalidate());
            map.addOnCameraMoveStartedListener(reason -> {
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) userMovedCamera = true;
            });
            if (!selectedMode() && (!filters.optString("city").isBlank() || !zone.isEmpty())) {
                LatLng center = zone.isEmpty() ? cityCenter(filters.optString("city")) : zone.get(0);
                map.setCameraPosition(new CameraPosition.Builder().target(center).zoom(12).build());
            }
            applyMapStyle();
            view.addOnDidFailLoadingMapListener(error -> {
                if (!loaded) hint.setText("Карта не загрузилась. Проверь интернет или выбери другой вид карты.");
            });
            updateHint();
            coverage = new TextView(this);
            coverage.setText(coverageText(listings.size(), without));
            coverage.setTextSize(11);
            coverage.setTextColor(MUTED);
            coverage.setLinkTextColor(BLUE);
            coverage.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            coverage.setPadding(dp(12), dp(4), dp(12), dp(4));
            root.addView(coverage);
        });
    }

    private boolean selectedMode() {
        return !selectedListingId.isBlank();
    }

    private CharSequence coverageText(int count, int missing) {
        String notice = (selectedMode() ? "" : "Координаты площадок: " + count + " · без координат: " + missing + "\n") +
            "© OpenFreeMap · © OpenMapTiles · © OpenStreetMap contributors";
        android.text.SpannableString text = new android.text.SpannableString(notice);
        String[][] links = { { "OpenFreeMap", "https://openfreemap.org" },
            { "OpenMapTiles", "https://openmaptiles.org" },
            { "OpenStreetMap contributors", "https://www.openstreetmap.org/copyright" } };
        for (String[] link : links) {
            int start = notice.indexOf(link[0]);
            text.setSpan(new android.text.style.URLSpan(link[1]), start, start + link[0].length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }

    private void showMapStyles() {
        String[] keys = { "bright", "positron", "liberty" };
        String[] descriptions = { "Bright · подробные улицы и здания", "Positron · светлая карта для цен",
            "Liberty · классическая цветная карта" };
        new AlertDialog.Builder(this).setTitle("Вид карты")
            .setSingleChoiceItems(descriptions, Arrays.asList(keys).indexOf(mapStyle), (dialog, which) -> {
                dialog.dismiss();
                String next = keys[which];
                Database db = ((DomTrackApp) getApplication()).db();
                JSONObject preferences = db.state("mapUi", new JSONObject());
                Json.put(preferences, "style", next);
                db.saveState("mapUi", preferences);
                if (next.equals(mapStyle) && loaded) return;
                mapStyle = next;
                mapStyleButton.setContentDescription("Вид карты: " + next);
                applyMapStyle();
            }).setNegativeButton("К карте", null).show();
    }

    private void applyMapStyle() {
        if (map == null) return;
        loaded = false;
        int request = ++styleRequest;
        updateHint();
        map.setStyle("https://tiles.openfreemap.org/styles/" + mapStyle, style -> {
            if (request != styleRequest || isFinishing() || isDestroyed()) return;
            MapBuildings.apply(style);
            MapBuildings.setVisible(style, selectedMode() && MapPresentation.hasCoordinates(selectedRecord) && perspective3d);
            loaded = true;
            updatePerspectiveButton();
            view.post(() -> {
                if (request == styleRequest && !isFinishing() && !isDestroyed()) frameCamera();
            });
            updateHint();
            drawing.invalidate();
        });
    }

    private LinearLayout selectedPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable card = surface(Color.WHITE, 0, 0);
        card.setCornerRadii(new float[] { dp(20), dp(20), dp(20), dp(20), 0, 0, 0, 0 });
        panel.setBackground(card);
        panel.setElevation(dp(6));
        selectedPrice = new TextView(this);
        selectedPrice.setTextSize(27);
        selectedPrice.setTypeface(null, Typeface.BOLD);
        selectedPrice.setTextColor(BLUE);
        selectedPrice.setContentDescription("Цена объекта");
        panel.addView(selectedPrice);
        selectedAddress = new TextView(this);
        selectedAddress.setTextSize(16);
        selectedAddress.setTextColor(INK);
        selectedAddress.setTypeface(null, Typeface.BOLD);
        selectedAddress.setPadding(0, dp(3), 0, dp(4));
        selectedAddress.setMaxLines(2);
        selectedAddress.setEllipsize(android.text.TextUtils.TruncateAt.END);
        selectedAddress.setContentDescription("Адрес объекта");
        panel.addView(selectedAddress);
        selectedSource = new TextView(this);
        selectedSource.setTextSize(12);
        selectedSource.setTextColor(MUTED);
        selectedSource.setPadding(0, 0, 0, dp(8));
        panel.addView(selectedSource);
        selectedQuality = new TextView(this);
        selectedQuality.setTextSize(12);
        selectedQuality.setTextColor(0xff8a5b12);
        selectedQuality.setPadding(dp(10), dp(7), dp(10), dp(7));
        selectedQuality.setBackground(surface(0xfffff6e6, 10, 0));
        panel.addView(selectedQuality);
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(10), 0, 0);
        routeButton = button("Маршрут", this::navigateToSelected);
        recordButton = button("Карточка", () -> openRecord(selectedRecord));
        styleButton(recordButton, true);
        actions.addView(routeButton, actionLayout(true));
        actions.addView(recordButton, actionLayout(false));
        panel.addView(actions);
        return panel;
    }

    private void updateSelectedPanel() {
        if (!selectedMode() || selectedPrice == null) return;
        updatePerspectiveButton();
        if (selectedRecord == null) {
            selectedPrice.setText("Объект недоступен");
            selectedAddress.setText("Запись не найдена в локальной базе");
            selectedSource.setText("");
            selectedQuality.setText("Точка объекта не показана");
            routeButton.setEnabled(false);
            recordButton.setEnabled(false);
            return;
        }
        selectedPrice.setText(price(selectedRecord));
        String address = selectedRecord.optString("address").trim();
        selectedAddress.setText((selectedRecord.optString("city").trim() + " · " +
            (address.isEmpty() ? "Адрес не указан" : address)).replaceFirst("^ · ", ""));
        String source = selectedRecord.optString("source");
        source = switch (source) {
            case "domria", "domria-web", "ria" -> "DIM.RIA";
            case "olx" -> "OLX";
            case "rieltor" -> "Rieltor";
            case "lun" -> "LUN";
            case "flatfy" -> "Flatfy";
            case "manual" -> "Вручную";
            default -> source.isBlank() ? "не указан" : source;
        };
        selectedSource.setText("Источник: " + source);
        selectedQuality.setText(switch (MapPresentation.precision(selectedRecord)) {
            case MISSING -> "Координаты не переданы. Карта показана без точки объекта.";
            case APPROXIMATE -> "≈ Примерное положение. Точный адрес дома не подтверждён.";
            case UNSPECIFIED -> "≈ Точка из объявления. Источник не указал точность координат.";
        });
        routeButton.setText(address.isEmpty() ? "Маршрут к точке" : "Маршрут по адресу");
        routeButton.setEnabled(!MapPresentation.routeUrl(selectedRecord).isBlank());
        recordButton.setEnabled(true);
        if (loaded) updateHint();
    }

    private void updatePerspectiveButton() {
        if (perspectiveButton == null) return;
        boolean available = MapPresentation.hasCoordinates(selectedRecord);
        perspectiveButton.setEnabled(available);
        styleButton(perspectiveButton, available && perspective3d);
        perspectiveButton.setTextSize(12);
        perspectiveButton.setContentDescription(!available ? "3D недоступно без координат объекта" :
            perspective3d ? "3D-здания включены. Переключить в 2D" : "Карта в 2D. Включить 3D-здания");
        if (loaded && map != null) MapBuildings.setVisible(map.getStyle(), available && perspective3d);
    }

    private void togglePerspective() {
        if (!MapPresentation.hasCoordinates(selectedRecord)) return;
        perspective3d = !perspective3d;
        updatePerspectiveButton();
        if (map != null) {
            map.setCameraPosition(new CameraPosition.Builder(map.getCameraPosition())
                .tilt(perspective3d ? 45 : 0).build());
            drawing.invalidate();
        }
    }

    private void navigateToSelected() {
        String url = MapPresentation.routeUrl(selectedRecord);
        if (url.isBlank()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "На телефоне не найдено приложение для маршрута", Toast.LENGTH_LONG).show();
        }
    }

    private void openRecord(JSONObject record) {
        if (record == null) return;
        Intent result = new Intent().putExtra("listingId", record.optString("id"));
        if (selectedMode()) result.putExtra("selectedMap", true);
        else result.putExtra("filters", filters.toString());
        setResult(RESULT_OK, result);
        finish();
    }

    private void frameCamera() {
        if (selectedMode()) frameSelected();
        else frameGlobalCandidates();
    }

    private void frameSelected() {
        if (!loaded || map == null || view.getWidth() == 0 || view.getHeight() == 0 ||
            selectedCameraFramed || userMovedCamera) return;
        if (MapPresentation.hasCoordinates(selectedRecord)) {
            double zoom = MapPresentation.precision(selectedRecord) == MapPresentation.Precision.APPROXIMATE ? 13 : 16;
            map.setCameraPosition(new CameraPosition.Builder().target(new LatLng(
                selectedRecord.optDouble("latitude"), selectedRecord.optDouble("longitude"))).zoom(zoom)
                .tilt(perspective3d ? 45 : 0).build());
            selectedCameraFramed = true;
        } else {
            String city = selectedRecord == null ? "" : selectedRecord.optString("city");
            boolean known = Arrays.stream(Sources.CITIES).anyMatch(c -> Bilingual.city(c[0]).equals(Bilingual.city(city)));
            if (known) map.setCameraPosition(new CameraPosition.Builder().target(cityCenter(city)).zoom(11).build());
            else map.moveCamera(CameraUpdateFactory.newLatLngBounds(new LatLngBounds.Builder()
                .include(new LatLng(44, 22)).include(new LatLng(52.5, 40.5)).build(), dp(32)));
        }
    }

    private void frameGlobalCandidates() {
        if (!loaded || map == null || view.getWidth() == 0 || view.getHeight() == 0 ||
            !filters.optString("city").isBlank() || !zone.isEmpty() ||
            globalCameraFramed || userMovedCamera) return;
        if (listings.isEmpty()) {
            // Keep the overview until coordinates arrive; do not create substitute markers.
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(new LatLngBounds.Builder()
                .include(new LatLng(44, 22)).include(new LatLng(52.5, 40.5)).build(), dp(32)));
            return;
        }
        LatLng first = new LatLng(listings.get(0).optDouble("latitude"), listings.get(0).optDouble("longitude"));
        LatLngBounds.Builder bounds = new LatLngBounds.Builder();
        boolean differentPosition = false;
        for (JSONObject record : listings) {
            LatLng point = new LatLng(record.optDouble("latitude"), record.optDouble("longitude"));
            bounds.include(point);
            if (point.getLatitude() != first.getLatitude() || point.getLongitude() != first.getLongitude()) {
                differentPosition = true;
            }
        }
        if (differentPosition) map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), dp(48)));
        else map.setCameraPosition(new CameraPosition.Builder().target(first).zoom(12).build());
        globalCameraFramed = true;
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        styleButton(b, false);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private GradientDrawable surface(int color, int radius, int border) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        if (border != 0) background.setStroke(dp(1), border);
        return background;
    }

    private void styleButton(Button button, boolean primary) {
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(new ColorStateList(new int[][] { new int[] { -android.R.attr.state_enabled }, new int[0] },
            new int[] { 0xff9aa9bc, primary ? Color.WHITE : BLUE }));
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22195cc5),
            surface(primary ? BLUE : Color.WHITE, 12, primary ? 0 : LINE), null));
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setPadding(dp(12), dp(6), dp(12), dp(6));
    }

    private LinearLayout.LayoutParams actionLayout(boolean gapAfter) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -2, 1);
        if (gapAfter) layout.setMargins(0, 0, dp(8), 0);
        return layout;
    }

    private void updateModeButtons() {
        for (Map.Entry<String, Button> entry : modeButtons.entrySet()) {
            Button control = entry.getValue();
            boolean selected = mode.equals(entry.getKey());
            styleButton(control, selected);
            control.setTextSize(13);
            control.setSelected(selected);
        }
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private void snapshot() {
        undo.push(new ArrayList<>(zone));
        if (undo.size() > 30) undo.removeLast();
    }

    private void updateHint() {
        if (selectedMode()) {
            hint.setText((loaded ? "" : "Загрузка карты… · ") +
                (MapPresentation.hasCoordinates(selectedRecord)
                    ? "Расположение из объявления · ≈ точность источником не подтверждена"
                    : "Координаты объекта не переданы · точка не показана"));
            return;
        }
        String text = switch (mode) {
            case "Пальцем" -> "Обведи область пальцем одним движением";
            case "Точки" -> "Нажимай на границу участка: минимум три точки";
            case "Прямоугольник" -> "Проведи от одного угла к противоположному";
            case "Править" -> "Перетаскивай точки границы";
            default -> "Нажми на цену или группу · приближай карту, чтобы разделить объекты";
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

    private String markerPrice(JSONObject record) {
        return (selectedMode() ? "≈ " : "") + price(record);
    }

    private void openMarker(JSONObject record) {
        if (selectedMode()) {
            openRecord(record);
            return;
        }
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

    private void openGroup(List<JSONObject> records) {
        if (records.size() == 1) {
            openMarker(records.get(0));
            return;
        }
        ListView rows = new ListView(this);
        rows.setContentDescription("Список объектов группы");
        rows.setPadding(dp(12), dp(4), dp(12), dp(4));
        rows.setDivider(null);
        TextView explanation = new TextView(this);
        explanation.setText("Выбери объект, чтобы открыть карточку. Полные цены указаны ниже.");
        explanation.setTextSize(13);
        explanation.setTextColor(MUTED);
        explanation.setPadding(dp(8), dp(4), dp(8), dp(12));
        rows.addHeaderView(explanation, null, false);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Объекты в группе · " + records.size())
            .setView(rows)
            .setNegativeButton("К карте", null)
            .create();
        ArrayList<JSONObject> ordered = new ArrayList<>(records);
        String currency = filters.optString("currency", "EUR");
        markerPricing.refreshDate();
        ordered.sort(Comparator.comparingDouble((JSONObject record) -> {
            double amount = markerPricing.converted(record);
            return Double.isFinite(amount) ? amount : Double.POSITIVE_INFINITY;
        }).thenComparing(record -> record.optString("currency"))
            .thenComparingDouble(record -> record.optDouble("price"))
            .thenComparing(record -> record.optString("id")));
        rows.setAdapter(new BaseAdapter() {
            public int getCount() { return ordered.size(); }
            public JSONObject getItem(int position) { return ordered.get(position); }
            public long getItemId(int position) { return position; }
            public View getView(int position, View recycled, ViewGroup parent) {
                JSONObject record = getItem(position);
                Button row;
                if (recycled instanceof Button button) row = button;
                else {
                    row = new Button(MapActivity.this);
                    styleButton(row, false);
                    row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    row.setTextSize(15);
                    row.setTextColor(INK);
                    row.setMinHeight(dp(80));
                    row.setPadding(dp(12), dp(10), dp(12), dp(10));
                    row.setLineSpacing(dp(3), 1);
                }
                String text = groupRowText(record, currency);
                android.text.SpannableString styled = new android.text.SpannableString(text);
                int priceEnd = text.indexOf('\n');
                styled.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, priceEnd, 0);
                styled.setSpan(new android.text.style.ForegroundColorSpan(BLUE), 0, priceEnd, 0);
                styled.setSpan(new android.text.style.RelativeSizeSpan(1.1f), 0, priceEnd, 0);
                row.setText(styled);
                row.setOnClickListener(v -> { dialog.dismiss(); openRecord(record); });
                return row;
            }
        });
        dialog.show();
        rows.getLayoutParams().height = Math.min(dp(420), getResources().getDisplayMetrics().heightPixels / 2);
        rows.requestLayout();
    }

    private String groupRowText(JSONObject record, String currency) {
        String originalCurrency = record.optString("currency");
        String amount = price(record);
        if (!originalCurrency.equals(currency) && Double.isFinite(Model.convert(
            record.optDouble("price"), originalCurrency, currency, mapQuote)))
            amount += "\nВ объявлении: " + String.format(Locale.forLanguageTag("ru"), "%,.0f %s",
                record.optDouble("price"), originalCurrency);
        String address = record.optString("address").trim();
        if (address.isEmpty()) address = record.optString("title");
        if (!record.optString("city").isBlank()) address = record.optString("city") + " · " + address;
        ArrayList<String> facts = new ArrayList<>();
        if (record.optInt("rooms") > 0) facts.add(record.optInt("rooms") + " комн.");
        if (record.optDouble("area", 0) > 0) facts.add(new java.text.DecimalFormat("0.#")
            .format(record.optDouble("area")) + " м²");
        if (record.optInt("floor") > 0) facts.add("этаж " + record.optInt("floor") +
            (record.optInt("floorsTotal") > 0 ? "/" + record.optInt("floorsTotal") : ""));
        String source = switch (record.optString("source")) {
            case "domria", "domria-web", "ria" -> "DIM.RIA";
            case "olx" -> "OLX";
            case "rieltor" -> "Rieltor";
            case "lun" -> "LUN";
            case "flatfy" -> "Flatfy";
            case "manual" -> "Вручную";
            default -> record.optString("source");
        };
        if (!source.isBlank()) facts.add(source);
        return amount + "\n" + address + "\n" + String.join(" · ", facts);
    }

    private class Drawing extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private PointF first, previous;
        private int vertex = -1;
        private final ArrayList<RectF> hitBoxes = new ArrayList<>();
        private final ArrayList<List<JSONObject>> hitGroups = new ArrayList<>();
        private List<JSONObject> pressedGroup;
        private MotionEvent markerDown;
        private boolean forwardingMapGesture;

        Drawing() {
            super(MapActivity.this);
            setContentDescription(selectedMode() ? "Карта выбранного объекта" : "Граница территории поиска");
        }

        protected void onDraw(Canvas canvas) {
            if (map == null) return;
            hitBoxes.clear();
            hitGroups.clear();
            paint.setTextSize(dp(selectedMode() ? 14 : 12));
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            ArrayList<MapPriceGroups.Entry> entries = new ArrayList<>();
            for (JSONObject r : listings) {
                PointF p = map
                    .getProjection()
                    .toScreenLocation(
                        new LatLng(r.optDouble("latitude"), r.optDouble("longitude"))
                    );
                if (p.x < 0 || p.x > getWidth() || p.y < 0 || p.y > getHeight()) continue;
                if (!selectedMode()) {
                    entries.add(new MapPriceGroups.Entry(r, p.x, p.y));
                    continue;
                }
                String label = labels.getOrDefault(r.optString("id"), r.optString("currency"));
                float width = paint.measureText(label) + dp(14);
                float left = Math.max(0, Math.min(getWidth() - width, p.x - width / 2));
                RectF box = new RectF(left, p.y - dp(29), left + width, p.y - dp(5));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(0xff956315);
                canvas.drawLine(p.x, p.y, box.centerX(), box.bottom, paint);
                canvas.drawCircle(p.x, p.y, dp(7), paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(box, dp(7), dp(7), paint);
                paint.setColor(Color.WHITE);
                canvas.drawText(label, box.left + dp(7), box.top + dp(16), paint);
                RectF touch = new RectF(box);
                touch.inset(-dp(8), -dp(12));
                hitBoxes.add(touch);
                hitGroups.add(List.of(r));
            }
            if (!selectedMode()) for (MapPriceGroups.Group group : MapPriceGroups.layout(entries,
                getWidth(), getHeight(), getResources().getDisplayMetrics().density,
                markerPricing, paint::measureText)) {
                RectF touch = new RectF(group.left, group.top, group.right, group.bottom);
                RectF box = new RectF(touch.left, touch.top + dp(6), touch.right, touch.bottom - dp(6));
                boolean multiple = group.members.size() > 1;
                paint.setColor(multiple ? 0xff143f83 : BLUE);
                if (!multiple) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(2));
                    canvas.drawLine(group.anchorX, group.anchorY, box.centerX(), box.bottom, paint);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(group.anchorX, group.anchorY, dp(3), paint);
                }
                canvas.drawRoundRect(box, dp(18), dp(18), paint);
                paint.setColor(Color.WHITE);
                float baseline = box.centerY() - (paint.ascent() + paint.descent()) / 2;
                canvas.save();
                canvas.clipRect(box);
                canvas.drawText(group.label, box.centerX() - paint.measureText(group.label) / 2, baseline, paint);
                canvas.restore();
                ArrayList<JSONObject> members = new ArrayList<>();
                for (MapPriceGroups.Entry entry : group.members) members.add(entry.record);
                hitBoxes.add(touch);
                hitGroups.add(members);
            }
            if (zone.isEmpty()) return;
            Path path = new Path();
            ArrayList<PointF> points = new ArrayList<>();
            for (LatLng p : zone) points.add(map.getProjection().toScreenLocation(p));
            path.moveTo(points.get(0).x, points.get(0).y);
            for (int i = 1; i < points.size(); i++) path.lineTo(points.get(i).x, points.get(i).y);
            if (points.size() >= 3) path.close();
            paint.setColor(0x26195cc5);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(BLUE);
            paint.setStrokeWidth(dp(3));
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            for (PointF p : points)
                canvas.drawCircle(p.x, p.y, dp(mode.equals("Править") ? 7 : 4), paint);
        }

        public boolean onTouchEvent(MotionEvent e) {
            if (map == null || !loaded) return false;
            if (mode.equals("Двигать")) {
                if (forwardingMapGesture) {
                    view.dispatchTouchEvent(e);
                    if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL)
                        forwardingMapGesture = false;
                    return true;
                }
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    pressedGroup = null;
                    clearMarkerDown();
                    for (int i = hitBoxes.size() - 1; i >= 0; i--) if (
                        hitBoxes.get(i).contains(e.getX(), e.getY())
                    ) {
                        pressedGroup = hitGroups.get(i);
                        markerDown = MotionEvent.obtain(e);
                        break;
                    }
                    return pressedGroup != null;
                }
                if (pressedGroup != null) {
                    if (e.getPointerCount() > 1 || (e.getActionMasked() == MotionEvent.ACTION_MOVE &&
                        Math.hypot(e.getX() - markerDown.getX(), e.getY() - markerDown.getY()) >
                            ViewConfiguration.get(getContext()).getScaledTouchSlop())) {
                        forwardingMapGesture = true;
                        pressedGroup = null;
                        view.dispatchTouchEvent(markerDown);
                        clearMarkerDown();
                        view.dispatchTouchEvent(e);
                        return true;
                    }
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                        performClick();
                        openGroup(pressedGroup);
                        pressedGroup = null;
                        clearMarkerDown();
                    } else if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        pressedGroup = null;
                        clearMarkerDown();
                    }
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

        private void clearMarkerDown() {
            if (markerDown != null) { markerDown.recycle(); markerDown = null; }
        }

        @Override protected void onDetachedFromWindow() {
            clearMarkerDown();
            super.onDetachedFromWindow();
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
        styleRequest++;
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
        b.putBoolean("globalCameraFramed", globalCameraFramed);
        b.putBoolean("userMovedCamera", userMovedCamera);
        b.putString("selectedListingId", selectedListingId);
        b.putBoolean("selectedCameraFramed", selectedCameraFramed);
        b.putBoolean("perspective3d", perspective3d);
    }
}

package ua.domtrack.mobile;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.json.*;

public class MainActivity extends Activity {

    private Database db;
    private LinearLayout root, body, nav;
    private TextView status;
    private JSONObject filters;
    private String tab = "Поиск";
    private String activeSavedSearchId = "";
    private boolean favoriteMapOpened;
    private String pendingBrowserUrl="";
    private String openedListingId="";
    private AlertDialog openedListingDialog;
    private int visibleCount = 30;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final Set<String> compare = new LinkedHashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private byte[] exportBytes;
    private String exportPassword;
    private boolean resumed;
    private String pendingPhotoListing;
    private final int INK = Color.rgb(27, 43, 59),
        GREEN = Color.rgb(25, 92, 197),
        MUTED = Color.rgb(100, 116, 135),
        BG = Color.rgb(245, 247, 250);

    public void onCreate(Bundle state) {
        super.onCreate(state);
        db = ((DomTrackApp) getApplication()).db();
        filters = db.state(
            "currentFilters",
            Json.object("city", "Запоріжжя", "kind", "both", "currency", "EUR")
        );
        if (state != null) {
            pendingPhotoListing = state.getString("pendingPhotoListing");
            activeSavedSearchId=state.getString("activeSavedSearchId","");
            favoriteMapOpened=state.getBoolean("favoriteMapOpened");
            tab=state.getString("workspaceTab","Поиск");
            pendingBrowserUrl=state.getString("pendingBrowserUrl","");
            openedListingId=state.getString("openedListingId","");
        }
        build();
        if(!openedListingId.isBlank()){String restore=openedListingId;handler.post(()->detail(restore));}
        handleIntent(getIntent());
    }

    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    protected void onResume() {
        super.onResume();
        resumed = true;
        lastAutoPoll = 0;
        AutoChecks.schedule(this, db);
        AutoChecks.catchUpIfDue(this, db);
        handler.post(searchRefresh);
        Alerts.deliver(this, db);
        render();
    }

    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(searchRefresh);
    }

    protected void onStop() {
        super.onStop();
    }

    protected void onDestroy() {
        stopped.set(true);
        if(openedListingDialog!=null)openedListingDialog.dismiss();
        io.shutdownNow();
        super.onDestroy();
    }

    private boolean searchWasRunning;
    private long lastSearchRender;
    private long lastAutoPoll;
    private String lastAutoState = "";
    private final Runnable searchRefresh = new Runnable() {
        public void run() {
            if (!resumed) return;
            boolean running = SearchService.RUNNING.get();
            long now = System.currentTimeMillis();
            if (now - lastAutoPoll >= 30000) {
                lastAutoPoll = now;
                AutoChecks.catchUpIfDue(MainActivity.this, db);
            }
            String autoState = db.state("autoCheck", new JSONObject()).toString();
            boolean automaticChanged = !autoState.equals(lastAutoState);
            lastAutoState = autoState;
            JSONObject search = db.state("activeSearch", new JSONObject());
            if (running || searchWasRunning) status.setText(search.optString("message", "Поиск…"));
            else if (automaticChanged) status.setText(AutoChecks.summary(db).split("\n")[0]);
            if (
                automaticChanged || running != searchWasRunning ||
                (running && System.currentTimeMillis() - lastSearchRender > 7000)
            ) {
                lastSearchRender = System.currentTimeMillis();
                render();
            }
            searchWasRunning = running;
            handler.postDelayed(this, 1500);
        }
    };

    private void handleIntent(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "https://[^\\s<>]+",
                    java.util.regex.Pattern.CASE_INSENSITIVE
                ).matcher(text);
                if (m.find()) addUrl(m.group());
            }
        }
        String id = intent.getStringExtra("listingId");
        if (id != null) handler.post(() -> detail(id));
    }

    private int dp(float n) {
        return (int) (getResources().getDisplayMetrics().density * n + .5f);
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private TextView text(String value, int size, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setPadding(0, dp(5), 0, dp(5));
        v.setTextIsSelectable(true);
        return v;
    }

    private TextView heading(String value) {
        TextView v = text(value, 23, INK);
        v.setTypeface(null, Typeface.BOLD);
        return v;
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(GREEN);
        b.setTextSize(14);
        b.setMinHeight(dp(48));
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        b.setStateListAnimator(null);
        buttonFace(b, false);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void buttonFace(Button b, boolean primary) {
        GradientDrawable face = new GradientDrawable();
        face.setColor(primary ? GREEN : Color.rgb(238, 244, 253));
        face.setCornerRadius(dp(12));
        b.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.argb(35, 25, 92, 197)), face, null));
        b.setTextColor(primary ? Color.WHITE : GREEN);
    }

    private String sourceName(String source) {
        switch (source) {
            case "domria": case "domria-web": case "ria": return "DIM.RIA";
            case "olx": return "OLX";
            case "rieltor": return "Rieltor.ua";
            case "lun": return "ЛУН";
            case "flatfy": return "Flatfy";
            case "obyava": return "OBYAVA";
            case "domik": return "Domik.ua";
            case "rem": return "REM.UA";
            default: return source.isBlank() ? "Источник не указан" : source;
        }
    }

    private TextView badge(String value) {
        TextView label = text(value, 12, GREEN);
        label.setTypeface(null, Typeface.BOLD);
        label.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(238, 244, 253));
        bg.setCornerRadius(dp(8));
        label.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, dp(6), 0, dp(6));
        label.setLayoutParams(lp);
        return label;
    }

    private void addButton(LinearLayout parent, String label, Runnable action) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(3), 0, dp(3));
        parent.addView(button(label, action), lp);
    }

    private LinearLayout card() {
        LinearLayout box = column();
        box.setPadding(dp(14), dp(10), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.rgb(226, 232, 239));
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(7), 0, dp(7));
        box.setLayoutParams(lp);
        return box;
    }

    private void build() {
        root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(8), dp(16), 0);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
            );
            v.setPadding(dp(16) + bars.left, dp(8) + bars.top, dp(16) + bars.right, bars.bottom);
            return insets;
        });
        TextView brand = heading("DomTrack UA");
        brand.setTextColor(GREEN);
        root.addView(brand);
        status = text("Самостоятельный поиск · данные на телефоне", 12, MUTED);
        root.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = column();
        body.setPadding(0, dp(4), 0, dp(12));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        nav = row();
        for (String name : List.of("Поиск", "Карта", "Избранное", "Слежение", "Ещё")) {
            Button b = button(name, () -> {
                if (name.equals("Карта")) { map(tab.equals("Избранное")); return; }
                tab = name;
                visibleCount = 30;
                render();
            });
            b.setTextSize(11);
            b.setPadding(0, 0, 0, 0);
            nav.addView(b, new LinearLayout.LayoutParams(0, dp(56), 1));
        }
        root.addView(nav);
        setContentView(root);
        render();
    }

    private void render() {
        if (body == null || isFinishing()) return;
        if (nav != null) for (int i=0;i<nav.getChildCount();i++) {
            Button item=(Button)nav.getChildAt(i);
            boolean selected=item.getText().toString().equals(tab)||(tab.equals("События")&&item.getText().toString().equals("Слежение"));
            item.setTypeface(null,selected?Typeface.BOLD:Typeface.NORMAL);
            buttonFace(item, selected);
        }
        body.removeAllViews();
        switch (tab) {
            case "Избранное":
                showListings(true);
                break;
            case "События":
                showEvents();
                break;
            case "Слежение":
                showTracking();
                break;
            case "Ещё":
                showMore();
                break;
            default:
                showListings(false);
        }
    }

    private String fmt(double n) {
        return String.format(Locale.forLanguageTag("ru"), "%,.0f", n);
    }

    private JSONObject quote() {
        return db.state("fx:" + Model.date(), new JSONObject());
    }

    private String price(JSONObject r) {
        String original = fmt(r.optDouble("price")) + " " + r.optString("currency");
        String currency = filters.optString("currency", "EUR");
        double converted = Model.convert(
            r.optDouble("price"),
            r.optString("currency"),
            currency,
            quote()
        );
        return currency.equals(r.optString("currency"))
            ? original
            : Double.isFinite(converted)
                ? fmt(converted) + " " + currency + " · " + original
                : original + " · курс НБУ недоступен";
    }

    private void showListings(boolean favorites) {
        LinearLayout actions = row();
        actions.addView(
            button(favorites?"К поиску":"Фильтры", ()->{if(favorites){tab="Поиск";render();}else filterDialog();}),
            new LinearLayout.LayoutParams(0, -2, 1)
        );
        actions.addView(button("Карта", () -> map(favorites)), new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(actions);
        body.addView(
            text(
                favorites?"Избранное · все города и бюджеты":filters.optString("city") +
                    " · " +
                    (filters.optString("kind").equals("house")
                            ? "дома"
                            : filters.optString("kind").equals("apartment")
                                ? "квартиры"
                                : "квартиры и дома") +
                    (filters.has("priceMin")
                            ? " · от " + fmt(filters.optDouble("priceMin")) + " " + filters.optString("currency")
                            : "") +
                    (filters.has("priceMax")
                            ? " · до " +
                            fmt(filters.optDouble("priceMax")) +
                            " " +
                            filters.optString("currency")
                            : ""),
                14,
                MUTED
            )
        );
        if (!favorites) {
            LinearLayout scan = row();
            Button searchButton = button(SearchService.RUNNING.get() ? "Остановить" : "Поиск", () -> {
                if (SearchService.RUNNING.get()) SearchService.stop(this);
                else startScan(true);
            });
            buttonFace(searchButton, true);
            scan.addView(
                searchButton,
                new LinearLayout.LayoutParams(0, -2, 1)
            );
            scan.addView(
                button("Источники и прогресс", this::sourceStatus),
                new LinearLayout.LayoutParams(0, -2, 1)
            );
            body.addView(scan);
            addButton(body, activeSavedSearchId.isBlank()?"Сохранить поиск и включить наблюдение":"Сохранить изменения поиска", this::saveSearch);
            ArrayList<JSONObject> sections=new ArrayList<>();
            List<Sources.Section> sourceSections=Workspace.safeSourceSections(filters);
            for (Sources.Section section:sourceSections) {
                JSONObject state=Json.copy(db.state("section:"+section.key(),new JSONObject()));
                Json.put(state,"source",section.source);sections.add(state);
            }
            TextView coverage=text(sourceSections.isEmpty()?"Выбери город в фильтрах, чтобы увидеть состояние источников.":Workspace.coverage(sections)+"\nЗагруженная подборка; охват всех объявлений не подтверждён.",12,MUTED);
            coverage.setOnClickListener(v->sourceStatus());body.addView(coverage);
        }
        LinearLayout ordering=row();
        String order=Workspace.sort(db.state("ui",new JSONObject()));
        ordering.addView(button((order.equals("price")?"✓ ":"")+"Дешевле",()->setSort("price")),new LinearLayout.LayoutParams(0,-2,1));
        ordering.addView(button((order.equals("newest")?"✓ ":"")+"Новые сначала",()->setSort("newest")),new LinearLayout.LayoutParams(0,-2,1));
        body.addView(ordering);
        List<JSONObject> rows = new ArrayList<>();
        JSONObject f = Workspace.resultFilters(filters,favorites);
        for (JSONObject r : db.listings())
            if (!r.optBoolean("hidden") && Model.matches(r, f, quote())) rows.add(r);
        rows = SemanticIndex.display(
            rows,
            quote(),
            filters.optString("currency", "EUR"),
            db.state("duplicateIndex", new JSONObject())
        );
        String sort = Workspace.sort(db.state("ui", new JSONObject()));
        if (sort.equals("price")) rows.sort(
            Comparator.comparingDouble(r -> {
                double p = Model.convert(
                    r.optDouble("price"),
                    r.optString("currency"),
                    filters.optString("currency", "EUR"),
                    quote()
                );
                return Double.isFinite(p) ? p : Double.MAX_VALUE;
            })
        );
        else rows.sort((a, b) -> b.optString("firstSeen").compareTo(a.optString("firstSeen")));
        body.addView(text("Найдено в локальной базе: " + rows.size(), 14, INK));
        addButton(body, "Сравнить отмеченные объекты", this::comparison);
        if (rows.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(heading("Сохрани поиск"));
            empty.addView(
                text(
                    "Проверь фильтры и запусти загрузку. История цен начнёт накапливаться с первого наблюдения. Избранное доступно без интернета.",
                    15,
                    MUTED
                )
            );
            body.addView(empty);
        }
        for (JSONObject r : rows.subList(0, Math.min(visibleCount, rows.size()))) {
            LinearLayout box = card();
            RemotePhotos.addThumbnail(box, r);
            box.addView(badge(sourceName(r.optString("source"))));
            TextView p = text(price(r), 21, GREEN);
            p.setTypeface(null, Typeface.BOLD);
            box.addView(p);
            box.addView(text(r.optString("title"), 17, INK));
            if (!r.optString("address").isBlank()) box.addView(text(
                r.optString("city") + " · " + r.optString("address"), 14, INK));
            box.addView(text(spec(r), 13, MUTED));
            if (!Model.verifiedPrice(r)) box.addView(text("Цена показана площадкой · исходная валюта продавца не подтверждена",11,MUTED));
            if (r.has("probableAlternatives")) {
                box.addView(
                    text(
                        "Вероятно один объект · " +
                            r.optJSONArray("probableAlternatives").length() +
                            " предложений · минимальная сравнимая цена",
                        12,
                        MUTED
                    )
                );
                addButton(box, "Все предложения и сравнение", () -> probableGroup(r));
            }
            if (r.has("confirmedAlternatives")) {
                box.addView(
                    text(
                        "Подтверждённый объект · предложений: " +
                            r.optJSONArray("confirmedAlternatives").length() +
                            " · показана минимальная цена",
                        12,
                        GREEN
                    )
                );
                addButton(box, "Цены и исходные предложения", () -> confirmedGroup(r));
            }
            box.addView(
                text(
                    "Проверено " + shortDate(r.optString("lastSeen")),
                    11,
                    MUTED
                )
            );
            LinearLayout line = row();
            line.addView(
                button("Открыть", () -> detail(r.optString("id"))),
                new LinearLayout.LayoutParams(0, -2, 1)
            );
            line.addView(
                button(r.optBoolean("favorite") ? "★" : "☆", () -> {
                    db.local(r.optString("id"), "favorite", !r.optBoolean("favorite"));
                    render();
                }),
                new LinearLayout.LayoutParams(dp(56), -2)
            );
            CheckBox cb = new CheckBox(this);
            cb.setContentDescription("Добавить к сравнению");
            cb.setChecked(compare.contains(r.optString("id")));
            cb.setOnCheckedChangeListener((v, checked) -> {
                if (checked) {
                    if (compare.size() >= 5) {
                        cb.setChecked(false);
                        toast("Для сравнения выбери до 5 объектов");
                        return;
                    }
                    compare.add(r.optString("id"));
                } else compare.remove(r.optString("id"));
            });
            line.addView(cb);
            box.addView(line);
            body.addView(box);
        }
        if (rows.size() > visibleCount) addButton(body, "Показать ещё", () -> {
            visibleCount += 30;
            render();
        });
        JSONObject last = db.state("lastCheck", new JSONObject());
        body.addView(text(AutoChecks.summary(db), 12, MUTED));
        addButton(body, "Состояние автопроверки", this::automaticStatus);
        body.addView(
            text(
                last.has("at")
                    ? "Последняя проверка: " +
                    shortDate(last.optString("at")) +
                    " · страниц: " +
                    last.optInt("pages") +
                    " · записей: " +
                    last.optInt("imported")
                    : "Фоновые проверки запланированы с интервалом 15 минут. Android может отложить запуск.",
                12,
                MUTED
            )
        );
    }

    private String spec(JSONObject r) {
        List<String> s = new ArrayList<>();
        s.add(r.optString("kind").equals("house") ? "Дом" : "Квартира");
        if (r.has("rooms")) s.add(r.optInt("rooms") + " комн.");
        if (r.has("area")) s.add(r.optDouble("area") + " м²");
        if (r.has("floor")) s.add("Этаж: " + r.optInt("floor"));
        if (r.has("floorsTotal")) s.add("Этажей в доме: " + r.optInt("floorsTotal"));
        if (!r.optString("district").isBlank()) s.add(r.optString("district"));
        return String.join(" · ", s);
    }

    private void setSort(String sort) {
        JSONObject ui=db.state("ui",new JSONObject());Json.put(ui,"sort",sort);db.saveState("ui",ui);visibleCount=30;render();
    }

    private String shortDate(String value) {
        try {
            return java.time.Instant.parse(value)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"));
        } catch (Exception e) {
            return value;
        }
    }

    private EditText input(LinearLayout parent, String label, String value, boolean numeric) {
        parent.addView(text(label, 12, MUTED));
        EditText field = new EditText(this);
        field.setText(value);
        field.setSingleLine(true);
        field.setTextSize(16);
        field.setInputType(
            numeric
                ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                : InputType.TYPE_CLASS_TEXT
        );
        parent.addView(field, new LinearLayout.LayoutParams(-1, dp(48)));
        return field;
    }

    private Spinner select(LinearLayout parent, String label, String[] items, int selected) {
        parent.addView(text(label, 12, MUTED));
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(
            new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items)
        );
        spinner.setSelection(Math.max(0, selected));
        parent.addView(spinner, new LinearLayout.LayoutParams(-1, dp(48)));
        return spinner;
    }

    private AlertDialog dialog(String title, LinearLayout content) {
        content.setPadding(dp(18), dp(8), dp(18), dp(18));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        return new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton("Закрыть", null)
            .create();
    }

    private void filterDialog() {
        LinearLayout form = column();
        String[] cities = Arrays.stream(Sources.CITIES)
            .map(c -> c[0])
            .toArray(String[]::new);
        Spinner city = select(
            form,
            "Город",
            cities,
            Arrays.asList(cities).indexOf(filters.optString("city"))
        );
        Spinner kind = select(
            form,
            "Тип жилья",
            new String[] { "Квартиры и дома", "Квартиры", "Дома" },
            List.of("both", "apartment", "house").indexOf(filters.optString("kind", "both"))
        );
        EditText district = input(
            form,
            "Район / адрес — русский или українська",
            filters.optString("district"),
            false
        );
        EditText query = input(
            form,
            "Слова в объявлении — RU / UA",
            filters.optString("query"),
            false
        );
        Spinner currency = select(
            form,
            "Валюта бюджета",
            new String[] { "EUR", "USD", "UAH" },
            List.of("EUR", "USD", "UAH").indexOf(filters.optString("currency", "EUR"))
        );
        Map<String, EditText> fields = new LinkedHashMap<>();
        String[][] definitions = {
            { "priceMin", "Цена от — минимум" },
            { "priceMax", "Цена до — максимум" },
            { "roomsMin", "Комнат от" },
            { "roomsMax", "Комнат до" },
            { "areaMin", "Площадь от, м²" },
            { "areaMax", "Площадь до, м²" },
            { "floorMin", "Этаж от" },
            { "floorMax", "Этаж до" },
            { "floorsTotalMin", "Этажность здания от" },
            { "floorsTotalMax", "Этажность здания до" },
        };
        for (int i = 0; i < definitions.length; i += 2) {
            LinearLayout pair = row();
            for (int j = i; j < i + 2; j++) {
                LinearLayout half = column();
                half.setPadding(j == i ? 0 : dp(6), 0, j == i ? dp(6) : 0, 0);
                String[] def = definitions[j];
                fields.put(def[0], input(half, def[1], filters.optString(def[0], ""), true));
                pair.addView(half, new LinearLayout.LayoutParams(0, -2, 1));
            }
            form.addView(pair);
        }
        Spinner seller = select(
            form,
            "Продавец",
            new String[] { "Любой / не указан", "Хозяин", "Застройщик", "Риелтор" },
            List.of("any", "owner", "developer", "agent").indexOf(
                filters.optString("sellerType", "any")
            )
        );
        String[] previous = { filters.optString("currency", "EUR") };
        currency.setOnItemSelectedListener(
            new android.widget.AdapterView.OnItemSelectedListener() {
                public void onNothingSelected(AdapterView<?> p) {}

                public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    String next = currency.getSelectedItem().toString();
                    if (next.equals(previous[0])) return;
                    for (String key : List.of("priceMin", "priceMax")) {
                        EditText e = fields.get(key);
                        if (!e.getText().toString().isBlank()) {
                            try {
                                double n = Double.parseDouble(
                                        e.getText().toString().replace(',', '.')
                                    ),
                                    converted = Model.convert(n, previous[0], next, quote());
                                if (
                                    !Double.isFinite(converted)
                                ) throw new IllegalArgumentException();
                                e.setText(String.format(Locale.ROOT, "%.2f", converted));
                            } catch (Exception ex) {
                                currency.setSelection(
                                    List.of("EUR", "USD", "UAH").indexOf(previous[0])
                                );
                                toast("Для пересчёта бюджета сначала обнови курс НБУ");
                                return;
                            }
                        }
                    }
                    previous[0] = next;
                }
            }
        );
        AlertDialog d = dialog("Фильтры поиска", form);
        d.setButton(AlertDialog.BUTTON_POSITIVE, "Применить", (x, w) -> {});
        d.show();
        d
            .getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                try {
                    JSONObject f = Json.object(
                        "city",
                        city.getSelectedItem().toString(),
                        "kind",
                        List.of("both", "apartment", "house").get(kind.getSelectedItemPosition()),
                        "district",
                        district.getText().toString().trim(),
                        "currency",
                        currency.getSelectedItem().toString(),
                        "sellerType",
                        List.of("any", "owner", "developer", "agent").get(
                            seller.getSelectedItemPosition()
                        )
                    );
                    Json.put(f, "query", query.getText().toString().trim());
                    for (Map.Entry<String, EditText> entry : fields.entrySet()) {
                        String s = entry.getValue().getText().toString().trim().replace(',', '.');
                        if (s.isEmpty()) continue;
                        double n = Double.parseDouble(s);
                        if (
                            !Double.isFinite(n) || n < 0 || n > 1e12
                        ) throw new IllegalArgumentException("Проверь числовые поля");
                        if (
                            (entry.getKey().startsWith("floor") ||
                                entry.getKey().startsWith("rooms")) &&
                            (n != Math.floor(n) || n > 1000)
                        ) throw new IllegalArgumentException(
                            "Комнаты и этажи: целые значения до 1000"
                        );
                        Json.put(f, entry.getKey(), n);
                    }
                    for (String k : List.of("price", "rooms", "area", "floor", "floorsTotal"))
                        if (
                            f.has(k + "Min") &&
                            f.has(k + "Max") &&
                            f.optDouble(k + "Min") > f.optDouble(k + "Max")
                        ) throw new IllegalArgumentException("Значение «от» больше «до»");
                    if (
                        Bilingual.city(filters.optString("city")).equals(
                            Bilingual.city(f.optString("city"))
                        ) &&
                        filters.has("polygon")
                    ) Json.put(f, "polygon", filters.opt("polygon"));
                    filters = f;
                    db.saveState("currentFilters", f);
                    visibleCount = 30;
                    d.dismiss();
                    render();
                } catch (Exception ex) {
                    toast(Scanner.safe(ex));
                }
            });
    }

    private void saveSearch() {
        JSONObject editing=db.searches().stream().filter(s->s.optString("id").equals(activeSavedSearchId)).findFirst().orElse(null);
        if (editing!=null) {
            new AlertDialog.Builder(this).setTitle("Сохранённый поиск").setItems(new String[]{"Обновить «"+editing.optString("name")+"»","Сохранить отдельную копию"},(d,index)->saveSearch(index==0?editing:null)).setNegativeButton("Отмена",null).show();
            return;
        }
        saveSearch(null);
    }

    private void saveSearch(JSONObject editing) {
        prompt(
            "Название поиска",
            editing==null?filters.optString("city") + " · " + filters.optString("currency"):editing.optString("name"),
            false,
            name -> {
                JSONObject saved = Workspace.savedSearch(editing,name,filters);
                db.saveSearch(saved);
                activeSavedSearchId=saved.optString("id");
                AutoChecks.targetsChanged(this, db);
                requestNotifications();
                toast("Поиск сохранён. Уведомления появятся после обнаружения новых изменений.");
                render();
            }
        );
    }

    private void startScan(boolean full) {
        if (SearchService.RUNNING.get()) {
            toast("Поиск уже выполняется");
            return;
        }
        requestNotifications();
        try {
            JSONObject observation = db.saveCurrentSearch(filters);
            // The explicit service performs this scan; it schedules catch-up after releasing its gate.
            AutoChecks.targetsChanged(this, db, false);
            SearchService.start(this, Json.copy(filters));
            boolean automatic = db.state("settings", new JSONObject()).optBoolean("background", true)
                && observation.optBoolean("enabled", true);
            status.setText("Полный поиск · можно выключить экран · автопроверка " +
                (automatic ? "включена" : "приостановлена"));
        } catch (Exception e) {
            toast("Поиск не запущен: " + Scanner.safe(e));
        }
    }

    private void requestNotifications() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 10);
    }

    private void showEvents() {
        body.addView(heading("Изменения"));
        addButton(body, "Разрешить уведомления и звук", () -> {
            requestNotifications();
            startActivity(
                new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
                    Settings.EXTRA_APP_PACKAGE,
                    getPackageName()
                )
            );
        });
        List<JSONObject> events = db.events(false);
        if (events.isEmpty()) body.addView(
            text(
                "Здесь появятся новые объявления и обнаруженные изменения. Первичная загрузка формирует исходную базу и не создаёт поток оповещений.",
                15,
                MUTED
            )
        );
        for (JSONObject e : events) {
            LinearLayout box = card();
            box.addView(text(e.optString("title"), 17, INK));
            box.addView(text(e.optString("message"), 14, MUTED));
            box.addView(text(shortDate(e.optString("createdAt")), 12, MUTED));
            addButton(box, "Открыть объект", () -> detail(e.optString("listingId")));
            body.addView(box);
        }
    }

    private void detail(String id) {
        JSONObject r = db.get(id);
        if (r == null) return;
        if(openedListingDialog!=null)openedListingDialog.dismiss();
        openedListingId=id;
        LinearLayout content = column();
        RemotePhotos.addGallery(content, r);
        content.addView(badge(sourceName(r.optString("source"))));
        content.addView(heading(price(r)));
        content.addView(text(r.optString("title"), 19, INK));
        content.addView(text(spec(r) + "\n" + r.optString("address"), 15, MUTED));
        ListingMapPreview.add(content, r, () -> mapListing(r));
        addButton(content,"Карта этого объекта",()->mapListing(r));
        content.addView(badge("Характеристики"));
        content.addView(text("Город: " + r.optString("city") +
            (r.optString("district").isBlank() ? "" : "\nРайон: " + r.optString("district")) +
            "\nПродавец: " + sellerName(r.optString("sellerType")), 14, INK));
        org.jsoup.nodes.Document descriptionDocument = org.jsoup.Jsoup.parse(r.optString("description").replace("\n", "<br>"));
        descriptionDocument.select("p,div,li,blockquote,h1,h2,h3,h4").append("<br>");
        String description = descriptionDocument.wholeText().replaceAll("\n{3,}", "\n\n").trim();
        if (!description.isBlank()) {
            content.addView(badge("Описание"));
            content.addView(text(description, 15, INK));
        }
        if (!Model.verifiedPrice(r)) content.addView(text("Это отображаемая цена площадки. Она сохраняется в истории, но не подтверждает изменение исходной цены продавца; уведомления о снижении и пороге по ней не создаются.",13,MUTED));
        content.addView(
            text(
                "Источник: " +
                    sourceName(r.optString("source")) +
                    "\nВпервые замечено: " +
                    shortDate(r.optString("firstSeen")) +
                    "\nПоследняя проверка: " +
                    shortDate(r.optString("lastSeen")),
                12,
                MUTED
            )
        );
        addButton(content, "Открыть исходное объявление", () -> open(r.optString("url")));
        addButton(content,"Поделиться объявлением",()->shareListing(r));
        if (!r.optString("contactPhone").isBlank()) addButton(
            content,
            "Позвонить: " + r.optString("contactPhone"),
            () ->
                startActivity(
                    new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + r.optString("contactPhone")))
                )
        );
        else content.addView(
            text(
                "Телефон не передан источником — проверь контакты в исходном объявлении.",
                13,
                MUTED
            )
        );
        if (!r.optString("contactEmail").isBlank()) content.addView(text("Email: " + r.optString("contactEmail"), 14, INK));
        content.addView(badge("Моё наблюдение"));
        addButton(
            content,
            r.optBoolean("favorite") ? "Убрать из избранного" : "Добавить в избранное",
            () -> {
                db.local(id, "favorite", !r.optBoolean("favorite"));
                toast("Избранное обновлено");
                render();
            }
        );
        JSONObject watch = r.optJSONObject("watch");
        Switch enabled = new Switch(this);
        enabled.setText("Следить за изменениями объекта");
        enabled.setChecked(watch != null && watch.optBoolean("enabled", true));
        enabled.setOnCheckedChangeListener((v, on) -> {
            setWatchEnabled(id,on);
            if (on) requestNotifications();
        });
        content.addView(enabled);
        addButton(content, "Задать порог цены", () -> threshold(id, r));
        EditText note = input(content, "Мои заметки", r.optString("note"), false);
        note.setSingleLine(false);
        note.setMinLines(3);
        addButton(content, "Сохранить заметку", () -> {
            db.local(id, "note", note.getText().toString());
            toast("Заметка сохранена");
        });
        addButton(content, "Запланировать просмотр", () -> {
            Intent i = new Intent(
                Intent.ACTION_INSERT,
                android.provider.CalendarContract.Events.CONTENT_URI
            )
                .putExtra(
                    android.provider.CalendarContract.Events.TITLE,
                    "Просмотр: " + r.optString("title")
                )
                .putExtra(
                    android.provider.CalendarContract.Events.EVENT_LOCATION,
                    r.optString("address")
                )
                .putExtra(android.provider.CalendarContract.Events.DESCRIPTION, r.optString("url"));
            try {
                startActivity(i);
            } catch (ActivityNotFoundException e) {
                toast("На телефоне не найден календарь");
            }
        });
        addButton(content, "Маршрут", () -> {
            if (r.has("latitude") && r.has("longitude")) open(
                "https://www.google.com/maps/dir/?api=1&destination=" +
                    r.optDouble("latitude") +
                    "," +
                    r.optDouble("longitude")
            );
            else open(
                "https://www.google.com/maps/search/?api=1&query=" +
                    Uri.encode(r.optString("city") + " " + r.optString("address"))
            );
        });
        visitTools(content, r);
        appendGroup(content, r);
        content.addView(heading("История наблюдений"));
        List<JSONObject> history = db.history(id);
        content.addView(new PriceChart(this, history), new LinearLayout.LayoutParams(-1, dp(180)));
        for (JSONObject h : history.subList(0, Math.min(30, history.size())))
            content.addView(
                text(
                    shortDate(h.optString("observedAt")) +
                        " · " +
                        fmt(h.optDouble("price")) +
                        " " +
                        h.optString("currency"),
                    13,
                    MUTED
                )
            );
        addButton(content, "Загрузить курсы НБУ на даты наблюдений", () ->
            async(() -> {
                Set<String> dates = new LinkedHashSet<>();
                for (JSONObject h : history)
                    dates.add(
                        java.time.Instant.parse(h.optString("observedAt"))
                            .atZone(Model.KYIV)
                            .toLocalDate()
                            .toString()
                    );
                int count = 0;
                for (String date : dates) {
                    if (count++ >= 30) break;
                    Fx.get(db, new Network(), date);
                }
                handler.post(() -> historicalFx(id));
                return "Исторические курсы загружены";
            })
        );
        content.addView(
            text(
                "График показывает наблюдения приложения. Изменение курса НБУ не считается изменением цены продавцом.",
                12,
                MUTED
            )
        );
        addButton(content, "Скрыть этот объект", () -> {
            db.local(id, "hidden", true);
            render();
            toast("Объект скрыт");
        });
        AlertDialog details=dialog("Объект", content);
        openedListingDialog=details;
        details.setOnDismissListener(d->{if(openedListingDialog==details){openedListingDialog=null;openedListingId="";}});
        details.show();
        if (details.getWindow() != null) details.getWindow().setLayout(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
    }

    private String sellerName(String value) {
        switch (value) {
            case "owner": return "Хозяин";
            case "developer": return "Застройщик";
            case "agent": return "Риелтор";
            default: return "Не указан источником";
        }
    }

    private void threshold(String id, JSONObject r) {
        LinearLayout form = column();
        JSONObject latest=db.get(id);
        JSONObject current = (latest==null?r:latest).optJSONObject("watch");
        EditText target = input(
            form,
            "Оповестить, когда цена будет не выше",
            current == null ? "" : current.optString("target"),
            true
        );
        Spinner currency = select(form, "Валюта порога", new String[] { "EUR", "USD", "UAH" }, Workspace.watchCurrencyIndex(current));
        AlertDialog d = dialog("Порог цены", form);
        d.setButton(-1, "Сохранить", (a, b) -> {});
        d.show();
        d
            .getButton(-1)
            .setOnClickListener(v -> {
                try {
                    double n = Double.parseDouble(target.getText().toString().replace(',', '.'));
                    if (n <= 0 || !Double.isFinite(n)) throw new IllegalArgumentException(
                        "Укажи положительный порог"
                    );
                    db.local(
                        id,
                        "watch",
                        Json.object(
                            "enabled",
                            true,
                            "target",
                            n,
                            "currency",
                            currency.getSelectedItem().toString()
                        )
                    );
                    requestNotifications();
                    AutoChecks.targetsChanged(this, db);
                    d.dismiss();
                    toast("Порог сохранён");
                    render();
                } catch (Exception e) {
                    toast("Проверь цену порога");
                }
            });
    }

    private void comparison() {
        LinearLayout content = column();
        if (compare.size() < 2) {
            toast("Отметь от 2 до 5 объектов галочками");
            return;
        }
        for (String id : compare) {
            JSONObject r = db.get(id);
            if (r == null) continue;
            LinearLayout box = card();
            box.addView(text(r.optString("title"), 17, INK));
            box.addView(text(price(r), 19, GREEN));
            box.addView(text(spec(r), 14, MUTED));
            if (r.optDouble("area", 0) > 0) box.addView(
                text(
                    "За м²: " +
                        fmt(r.optDouble("price") / r.optDouble("area")) +
                        " " +
                        r.optString("currency"),
                    14,
                    INK
                )
            );
            box.addView(text(r.optString("note"), 13, MUTED));
            content.addView(box);
        }
        addButton(
            content,
            "Подтвердить: выбранные объявления — один объект",
            this::confirmCompared
        );
        addButton(content, "Очистить сравнение", () -> {
            compare.clear();
            render();
        });
        dialog("Сравнение объектов", content).show();
    }

    private void setWatchEnabled(String id,boolean enabled) {
        JSONObject latest=db.get(id);if(latest==null)return;
        JSONObject current=latest.optJSONObject("watch");
        JSONObject next=current==null?new JSONObject():Json.copy(current);
        Json.put(next,"enabled",enabled);db.local(id,"watch",next);AutoChecks.targetsChanged(this,db);
    }

    private void showTracking() {
        body.addView(heading("Слежение"));
        body.addView(text(AutoChecks.summary(db),13,MUTED));
        addButton(body,"Состояние автопроверки",this::automaticStatus);
        addButton(body,"События и изменения",()->{tab="События";render();});
        body.addView(heading("Наблюдаемые объекты"));
        int count=0;
        for(JSONObject r:db.listings()) {
            JSONObject watch=r.optJSONObject("watch");
            if(watch==null)continue;
            count++;
            LinearLayout box=card();
            box.addView(text(r.optString("title"),17,INK));
            box.addView(text(price(r)+" · "+r.optString("city"),16,GREEN));
            box.addView(text(spec(r),12,MUTED));
            if(watch.has("target"))box.addView(text("Порог: "+fmt(watch.optDouble("target"))+" "+watch.optString("currency","EUR"),13,INK));
            if(!Model.verifiedPrice(r))box.addView(text("Цена отображаемая: подтверждённый сигнал о снижении или пороге по ней не создаётся.",12,MUTED));
            Switch active=new Switch(this);active.setText("Проверять объект");active.setChecked(watch.optBoolean("enabled",true));
            active.setOnCheckedChangeListener((v,on)->setWatchEnabled(r.optString("id"),on));
            box.addView(active);
            addButton(box,"Открыть объект",()->detail(r.optString("id")));
            addButton(box,"Изменить порог",()->threshold(r.optString("id"),db.get(r.optString("id"))));
            body.addView(box);
        }
        if(count==0)body.addView(text("В карточке интересующего объекта включи «Следить за изменениями объекта» или задай порог цены.",14,MUTED));
        body.addView(heading("Сохранённые поиски"));
        showSavedSearches();
    }

    private void showSavedSearches() {
        if(db.searches().isEmpty())body.addView(text("Сохрани параметры на вкладке «Поиск», чтобы получать новые подходящие объекты.",14,MUTED));
        for (JSONObject s : db.searches()) {
            LinearLayout box = card();
            box.addView(text(s.optString("name"), 17, INK));
            if (CurrentSearch.isCurrent(s)) box.addView(text(
                "Параметры обновляются кнопкой «Поиск». Выключи переключатель, чтобы приостановить автопроверку.", 12, MUTED));
            Switch active = new Switch(this);
            active.setText("Проверять и уведомлять");
            active.setChecked(s.optBoolean("enabled", true));
            active.setOnCheckedChangeListener((v, on) -> {
                JSONObject latest = db.searches().stream().filter(row ->
                    row.optString("id").equals(s.optString("id"))).findFirst().orElse(null);
                if (latest == null) return;
                Json.put(latest, "enabled", on);
                db.saveSearch(latest);
                AutoChecks.targetsChanged(this, db);
            });
            box.addView(active);
            addButton(box, "Открыть поиск", () -> {
                activeSavedSearchId=CurrentSearch.isCurrent(s)?"":s.optString("id");
                filters = Json.copy(s.optJSONObject("filters"));
                db.saveState("currentFilters", filters);
                tab = "Поиск";
                render();
            });
            addButton(box,CurrentSearch.isCurrent(s)?"Изменить параметры":"Изменить фильтры и название",()->{
                activeSavedSearchId=CurrentSearch.isCurrent(s)?"":s.optString("id");filters=Json.copy(s.optJSONObject("filters"));db.saveState("currentFilters",filters);tab="Поиск";render();filterDialog();
            });
            if (!CurrentSearch.isCurrent(s)) addButton(box, "Удалить поиск", () -> {
                db.deleteSearch(s.optString("id"));
                if(activeSavedSearchId.equals(s.optString("id")))activeSavedSearchId="";
                AutoChecks.targetsChanged(this, db);
                render();
            });
            body.addView(box);
        }
    }

    private void showMore() {
        body.addView(heading("Инструменты и настройки"));
        addButton(body,"Сохранённые поиски и слежение",()->{tab="Слежение";render();});
        addButton(body, "Добавить ссылку на объявление", () -> addUrl(""));
        addButton(body, "Добавить объект вручную", this::manual);
        addButton(body, "Источники и ход обхода", this::sourceStatus);
        addButton(body, "Состояние автопроверки", this::automaticStatus);
        addButton(body, "Проверить вероятные дубли", this::duplicateCandidates);
        body.addView(
            text(
                db
                    .state("aiStatus", new JSONObject())
                    .optString("message", "Многоязычный ИИ: анализ после полного поиска"),
                13,
                MUTED
            )
        );
        addButton(body, "Повторить анализ ИИ", () -> {
            SemanticIndex.request(this, db);
            toast("Анализ ИИ запущен на телефоне");
        });
        addButton(body, "Фон, уведомления и экономия заряда", this::settings);
        addButton(body, "API-ключ DIM.RIA", this::keyDialog);
        addButton(body, "Обновить официальный курс НБУ", () ->
            async(() -> {
                Fx.get(db, new Network(), Model.date());
                return "Курс НБУ обновлён";
            })
        );
        addButton(body, "Зашифрованная резервная копия", () ->
            prompt("Пароль копии: от 10 символов", "", true, password ->
                async(() -> {
                    exportBytes = Backup.encrypt(db.exportData(), password.toCharArray());
                    handler.post(() -> createDocument("DomTrack-backup-" + Model.date() + ".json"));
                    return "Выбери место сохранения";
                })
            )
        );
        addButton(body, "Импорт / восстановление с ПК", () -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("application/json")
                .addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, 22);
        });
        addButton(body, "Экспорт объявлений для ПК", () ->
            async(() -> {
                JSONObject exported = db.exportData();
                Json.put(exported, "format", "domtrack-export");
                exportBytes = exported.toString(2).getBytes(StandardCharsets.UTF_8);
                handler.post(() -> createDocument("DomTrack-for-PC-" + Model.date() + ".json"));
                return "Экспорт содержит личные объявления и заметки";
            })
        );
        body.addView(
            text(
                "Телефон выполняет поиск самостоятельно. Обмен файлами с ПК — дополнительная возможность. API-ключ в резервную копию не включается.",
                13,
                MUTED
            )
        );
        body.addView(
            text(
                "DomTrack UA Android 0.2.1 · локальная версия\nКарты: © OpenStreetMap contributors",
                12,
                MUTED
            )
        );
    }

    private void settings() {
        LinearLayout form = column();
        JSONObject settings = db.state("settings", new JSONObject());
        toggle(form, "Автоматические проверки каждые 15 минут", settings, "background", true);
        toggle(form, "Только Wi-Fi / безлимитная сеть", settings, "wifiOnly", false);
        addButton(form, "Состояние автопроверки", this::automaticStatus);
        toggle(form, "Тихие часы: 23:00–08:00", settings, "quiet", true);
        for (int i = 0; i < Alerts.TYPES.length; i++) toggle(
            form,
            Alerts.TITLES[i],
            settings,
            "alert_" + Alerts.TYPES[i],
            true
        );
        addButton(form, "Настроить звук и вибрацию по типам событий", () ->
            startActivity(
                new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
                    Settings.EXTRA_APP_PACKAGE,
                    getPackageName()
                )
            )
        );
        form.addView(
            text(
                "Android может сдвигать время запуска. Короткая проверка обрабатывает 2 страницы по мобильной сети или 4 по Wi-Fi, затем сохраняет очередь. Кнопка «Поиск» обходит все доступные страницы подключённых разделов и продолжает работу с выключенным экраном. Прогресс виден в уведомлении; остановка сохраняет очередь. Расход батареи зависит от числа поисков и ответа сайтов.",
                13,
                MUTED
            )
        );
        toggle(form, "Локальный ИИ для русских и украинских описаний", settings, "localAi", true);
        dialog("Автоматическая работа", form).show();
    }

    private void toggle(
        LinearLayout parent,
        String label,
        JSONObject settings,
        String key,
        boolean fallback
    ) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(15);
        s.setPadding(0, dp(12), 0, dp(12));
        s.setChecked(settings.optBoolean(key, fallback));
        s.setOnCheckedChangeListener((v, on) -> {
            Json.put(settings, key, on);
            db.saveState("settings", settings);
            ((DomTrackApp) getApplication()).schedule();
            AutoChecks.catchUpIfDue(this, db);
        });
        parent.addView(s);
    }

    private void automaticStatus() {
        LinearLayout form = column();
        TextView message = text("Получение состояния планировщика…", 14, INK);
        form.addView(message);
        Runnable refresh = () -> io.execute(() -> {
            try {
                String result = AutoCheckDiagnostics.read(this, db);
                handler.post(() -> { if (!isFinishing()) message.setText(result); });
            } catch (Exception e) {
                handler.post(() -> message.setText(AutoChecks.summary(db)
                    + "\nНе удалось прочитать состояние планировщика: " + Scanner.safe(e)));
            }
        });
        addButton(form, "Обновить статус", refresh);
        addButton(form, "Восстановить расписание", () -> {
            AutoChecks.targetsChanged(this, db);
            refresh.run();
        });
        addButton(form, "Настройки Android для DomTrack", () -> startActivity(
            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        dialog("Автоматические проверки", form).show();
        refresh.run();
    }

    private void keyDialog() {
        LinearLayout form = column();
        Secrets secrets = new Secrets(this);
        form.addView(
            text(
                secrets.configured()
                    ? "API-ключ сохранён на этом телефоне. Введи новый, чтобы заменить его."
                    : "Ключ с компьютера автоматически не переносится. Введи собственный ключ из кабинета RIA.",
                14,
                MUTED
            )
        );
        EditText field = input(form, "API-ключ", "", false);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        addButton(form, "Сохранить ключ", () ->
            async(() -> {
                secrets.set(field.getText().toString().trim());
                handler.post(() -> field.setText(""));
                return "Ключ сохранён в зашифрованном виде";
            })
        );
        addButton(form, "Удалить ключ", () ->
            async(() -> {
                secrets.set("");
                return "Ключ удалён";
            })
        );
        addButton(form, "Загрузить следующую порцию API", () ->
            async(() -> "Обработано объектов RIA: " + new Ria(this, db).next(filters))
        );
        form.addView(
            text(
                "Официальный API использует отдельные квоты. Открытые каталоги работают независимо от ключа.",
                13,
                MUTED
            )
        );
        dialog("DIM.RIA", form).show();
    }

    private void sourceStatus() {
        LinearLayout content = column();
        List<Sources.Section> sections=Workspace.safeSourceSections(filters);
        if(sections.isEmpty())content.addView(text("Выбери город в фильтрах, чтобы увидеть состояние источников.",14,MUTED));
        else if (sections.stream().noneMatch(section -> section.source.equals("rem")))
            content.addView(text("REM.UA: для выбранного города открытый раздел пока не подключён.", 13, MUTED));
        for (Sources.Section section : sections) {
            JSONObject s = db.state("section:" + section.key(), new JSONObject());
            LinearLayout box = card();
            box.addView(
                text(
                    sourceName(section.source) + " · " + (section.kind.equals("house") ? "дома" : "квартиры"),
                    17,
                    INK
                )
            );
            box.addView(
                text(
                    "Проверено: " +
                        shortDate(s.optString("checkedAt", "ещё не проверено")) +
                        "\nСледующая страница: " +
                        s.optInt("nextPage", 1) +
                        "\n" +
                        (s.optBoolean("complete")
                                ? "Обход раздела завершён"
                                : "Полный обход ещё не завершён") +
                        (s.optString("error").isBlank() ? "" : "\n" + s.optString("error")),
                    13,
                    MUTED
                )
            );
            if (section.source.equals("lun")) box.addView(
                text(
                    "LUN: доступен открытый каталог; robots.txt сейчас ограничивает следующие страницы.",
                    13,
                    MUTED
                )
            );
            if (section.source.equals("olx")) box.addView(
                text(
                    "Доступ к каталогу зависит от ограничений OLX. Если сайт не отдаёт данные, причина будет показана в результате проверки.",
                    13,
                    MUTED
                )
            );
            addButton(box, "Открыть каталог", () -> open(section.base));
            content.addView(box);
        }
        content.addView(
            text(
                "Rieltor — города списка; REM.UA — 21 город с открытыми разделами; LUN и Flatfy — 22 областных центра; DOM.RIA, OBYAVA, Domik и OLX — Запорожье. Обход зависит от доступности страниц площадки. LUN может оставаться частичным. Цены агрегаторов и исходных площадок имеют отдельную историю; одинаковые исходные ссылки объединяются в результатах с минимальной сопоставимой ценой. Полнота всего Интернета не подтверждена.",
                13,
                MUTED
            )
        );
        dialog("Охват источников", content).show();
    }

    private void addUrl(String initial) {
        prompt("Ссылка на объявление", initial, false,this::importListingUrl);
    }

    private void importListingUrl(String url) {
        io.execute(()->{
            try {
                int count=new Scanner(this,db).importUrl(url);
                if(count<1)throw new IllegalArgumentException("Поддерживаемые данные объекта не найдены");
                handler.post(()->{toast("Загружено объектов: "+count);render();});
            } catch(Exception error) {
                String message=Scanner.safe(error);
                handler.post(()->{
                    if(isFinishing()||isDestroyed())return;
                    AlertDialog.Builder choice=new AlertDialog.Builder(this).setTitle("Проверка объявления")
                        .setMessage(message+"\n\nМожно открыть доступную публичную страницу и прочитать её после загрузки. Это ручное добавление; фоновые проверки используют API и публичный сканер.")
                        .setNegativeButton("Закрыть",null);
                    if(ListingBrowserPolicy.target(url)!=null)choice.setPositiveButton("Прочитать страницу",(d,w)->openListingBrowser(url));
                    try{Network.safe(url);choice.setNeutralButton("Открыть оригинал",(d,w)->open(url));}catch(Exception ignored){}
                    choice.show();
                });
            }
        });
    }

    private void openListingBrowser(String url) {
        ListingBrowserPolicy.Target target=ListingBrowserPolicy.target(url);
        if(target==null){toast("Встроенный просмотр доступен для отдельных объявлений DIM.RIA и OLX");return;}
        pendingBrowserUrl=target.url;
        startActivityForResult(new Intent(this,ListingBrowserActivity.class).putExtra("url",target.url),25);
    }

    private void manual() {
        LinearLayout form = column();
        EditText url = input(form, "HTTPS-ссылка", "", false),
            title = input(form, "Заголовок", "", false),
            city = input(form, "Город", filters.optString("city"), false),
            amount = input(form, "Полная цена объекта", "", true);
        Spinner currency = select(form, "Валюта", new String[] { "EUR", "USD", "UAH" }, 0),
            kind = select(form, "Тип", new String[] { "Квартира", "Дом" }, 0);
        EditText phone = input(form, "Открытый контактный телефон", "", false);
        AlertDialog d = dialog("Добавить наблюдение", form);
        d.setButton(-1, "Сохранить", (a, b) -> {});
        d.show();
        d
            .getButton(-1)
            .setOnClickListener(v -> {
                try {
                    JSONObject r = Json.object(
                        "url",
                        url.getText().toString(),
                        "title",
                        title.getText().toString(),
                        "city",
                        city.getText().toString(),
                        "price",
                        Double.parseDouble(amount.getText().toString().replace(',', '.')),
                        "currency",
                        currency.getSelectedItem().toString(),
                        "kind",
                        kind.getSelectedItemPosition() == 0 ? "apartment" : "house",
                        "deal",
                        "sale",
                        "source",
                        "manual",
                        "observedAt",
                        Model.now(),
                        "contactPhone",
                        phone.getText().toString()
                    );
                    Model.validate(r);
                    db.ingest(List.of(r), false);
                    d.dismiss();
                    render();
                } catch (Exception e) {
                    toast(Scanner.safe(e));
                }
            });
    }

    private void map() { map(false); }

    private void map(boolean favorites) {
        favoriteMapOpened=favorites;
        startActivityForResult(
            new Intent(this, MapActivity.class).putExtra("filters", Workspace.resultFilters(filters,favorites).toString()),
            24
        );
    }

    private void mapListing(JSONObject listing) {
        favoriteMapOpened=false;
        startActivityForResult(new Intent(this,MapActivity.class)
            .putExtra("selectedListingId",listing.optString("id"))
            .putExtra("filters",Json.object("currency",filters.optString("currency","EUR")).toString()),24);
    }

    private void shareListing(JSONObject listing) {
        try {
            String url=listing.optString("url");Network.safe(url);
            String value=listing.optString("title")+"\n"+fmt(listing.optDouble("price"))+" "+listing.optString("currency")+"\n"+listing.optString("city")+" · "+spec(listing)+"\n"+url;
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,value),"Поделиться объявлением"));
        } catch(Exception error) { toast("Исходная ссылка недоступна для отправки"); }
    }

    private String asset(String name) throws IOException {
        try (InputStream in = getAssets().open(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void prompt(String title, String initial, boolean secret, Consumer<String> action) {
        LinearLayout form = column();
        EditText e = input(form, title, initial, false);
        if (secret) e.setInputType(
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        AlertDialog d = dialog(title, form);
        d.setButton(-1, "Продолжить", (a, b) -> {});
        d.show();
        d
            .getButton(-1)
            .setOnClickListener(v -> {
                String s = e.getText().toString().trim();
                if (s.isBlank()) {
                    e.setError("Заполни поле");
                    return;
                }
                d.dismiss();
                action.accept(s);
            });
    }

    private interface Task {
        String run() throws Exception;
    }

    private void async(Task task) {
        io.execute(() -> {
            try {
                String result = task.run();
                handler.post(() -> {
                    toast(result);
                    render();
                });
            } catch (Exception e) {
                handler.post(() -> toast(Scanner.safe(e)));
            }
        });
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private void open(String value) {
        try {
            Uri uri = Uri.parse(value);
            if (!"https".equals(uri.getScheme())) throw new IllegalArgumentException();
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            toast("Не удалось открыть ссылку");
        }
    }

    private void createDocument(String name) {
        startActivityForResult(
            new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, name)
                .addCategory(Intent.CATEGORY_OPENABLE),
            21
        );
    }

    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if(request==25) {
            String expected=pendingBrowserUrl;pendingBrowserUrl="";
            if(result!=RESULT_OK||data==null)return;
            String html=data.getStringExtra("html"),actual=data.getStringExtra("url");
            try {
                ListingBrowserPolicy policy=new ListingBrowserPolicy(expected);
                if(!policy.sameListing(actual)||html==null||html.getBytes(StandardCharsets.UTF_8).length>256*1024)throw new IllegalArgumentException("Исходная страница не подтверждена");
                async(()->{
                    List<JSONObject> candidates=Sources.importRecords(html,expected,actual);
                    List<JSONObject> matched=candidates.stream().filter(r->policy.sameListing(r.optString("url"))).toList();
                    if(matched.size()!=1)throw new IllegalArgumentException("Не удалось однозначно подтвердить цену и исходный объект");
                    int count=db.ingest(matched,false);
                    if(count<1)throw new IllegalArgumentException("Данные открытой страницы не сохранены");
                    return "Объект добавлен из открытой страницы. Фоновый доступ зависит от площадки.";
                });
            }catch(Exception error){toast(Scanner.safe(error));}
            return;
        }
        if (request == 24 && result == RESULT_OK && data != null) {
            String returnedFilters=data.getStringExtra("filters");
            if(!favoriteMapOpened&&returnedFilters!=null){filters = Json.obj(returnedFilters);db.saveState("currentFilters", filters);}
            favoriteMapOpened=false;
            render();
            String id = data.getStringExtra("listingId");
            if (id != null) detail(id);
            return;
        }
        if(request==24)favoriteMapOpened=false;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == 23) {
            String listing = pendingPhotoListing;
            pendingPhotoListing = null;
            if (listing != null) async(() -> {
                JSONObject state = db.state(
                    "photos:" + listing,
                    Json.object("items", new JSONArray())
                );
                JSONArray photos = state.optJSONArray("items");
                if (photos.length() >= 3) throw new IllegalArgumentException(
                    "В этой версии до трёх личных фотографий на объект"
                );
                photos.put(Photos.selected(this, uri));
                db.saveState("photos:" + listing, state);
                return "Фотография сохранена на телефоне";
            });
            return;
        }
        if (request == 21) {
            byte[] bytes = exportBytes;
            exportBytes = null;
            if (bytes == null) return;
            async(() -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IOException("Файл недоступен");
                    out.write(bytes);
                }
                return "Файл сохранён";
            });
        } else if (request == 22) async(() -> {
            String text;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Файл недоступен");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) != -1) {
                    if (out.size() + n > 16 * 1024 * 1024) throw new IOException(
                        "Файл больше 16 МБ"
                    );
                    out.write(b, 0, n);
                }
                text = out.toString(StandardCharsets.UTF_8);
            }
            JSONObject object = Json.obj(text);
            if (object.optString("format").equals("domtrack-encrypted")) {
                String encrypted = text;
                handler.post(() ->
                    prompt("Пароль резервной копии", "", true, password ->
                        async(() -> {
                            int count = Backup.restore(db, Backup.decrypt(encrypted, password.toCharArray()));
                            AutoChecks.targetsChanged(this, db);
                            return "Восстановлено записей: " + count;
                        })
                    )
                );
                return "Введи пароль копии";
            }
            int count = Backup.restore(db, object);
            AutoChecks.targetsChanged(this, db);
            return "Импортировано записей: " + count;
        });
    }

    private void confirmCompared() {
        if (compare.size() < 2) {
            toast("Отметь хотя бы два объявления");
            return;
        }
        List<JSONObject> rows = compare.stream().map(db::get).filter(Objects::nonNull).toList();
        if (
            rows
                .stream()
                .map(r -> Model.normalized(r.optString("city")) + ":" + r.optString("kind"))
                .distinct()
                .count() !=
            1
        ) {
            toast("Для объединения нужны один город и один тип жилья");
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Это один объект?")
            .setMessage(
                "После подтверждения в подборке будет показана самая низкая сопоставимая цена. Исходные объявления и их истории сохранятся."
            )
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Подтверждаю", (d, w) -> {
                String group = "user-" + UUID.randomUUID();
                for (JSONObject r : rows) db.local(r.optString("id"), "sameGroup", group);
                compare.clear();
                render();
                toast("Совпадение подтверждено");
            })
            .show();
    }

    private void confirmedGroup(JSONObject display) {
        LinearLayout content = column();
        for (JSONObject record : Json.objects(display.optJSONArray("confirmedAlternatives"))) {
            content.addView(
                text(
                    price(record) +
                        " · " +
                        record.optString("source") +
                        "\n" +
                        record.optString("title") +
                        "\nПроверено: " +
                        shortDate(record.optString("lastSeen")),
                    16,
                    INK
                )
            );
            if (List.of("flatfy", "lun").contains(record.optString("source"))) content.addView(
                text(
                    "Цена по каталогу агрегатора; на исходной площадке она могла измениться.",
                    13,
                    MUTED
                )
            );
            addButton(content, "История и карточка", () -> detail(record.optString("id")));
            addButton(content, "Открыть исходную площадку", () -> open(record.optString("url")));
        }
        dialog("Цены и исходные предложения", content).show();
    }

    private void probableGroup(JSONObject display) {
        LinearLayout content = column();
        List<JSONObject> records = Json.objects(display.optJSONArray("probableAlternatives"));
        content.addView(
            text(
                "ИИ обнаружил сходство описаний и характеристик. Исходные объявления сохранены отдельно.",
                14,
                MUTED
            )
        );
        for (JSONObject record : records) {
            content.addView(
                text(
                    price(record) +
                        " · " +
                        record.optString("source") +
                        "\n" +
                        record.optString("title"),
                    16,
                    INK
                )
            );
            addButton(content, "Открыть предложение", () -> detail(record.optString("id")));
        }
        addButton(content, "Сравнить и подтвердить", () -> {
            compare.clear();
            for (JSONObject record : records) compare.add(record.optString("id"));
            comparison();
        });
        addButton(content, "Показать эти объявления отдельно", () -> {
            for (int i = 0; i < records.size(); i++) for (
                int j = i + 1;
                j < records.size();
                j++
            ) db.saveState(
                "different:" +
                    SemanticIndex.pairKey(
                        records.get(i).optString("id"),
                        records.get(j).optString("id")
                    ),
                Json.object("different", true)
            );
            db.saveState("duplicateIndex", new JSONObject());
            SemanticIndex.request(this, db);
            render();
            toast("Объявления показаны отдельно");
        });
        dialog("Предложения одного объекта?", content).show();
    }

    private void duplicateCandidates() {
        LinearLayout content = column();
        Map<String, List<JSONObject>> buckets = new LinkedHashMap<>();
        for (JSONObject r : db.listings()) {
            String address = Dedupe.address(r);
            if (address.isBlank()) continue;
            String key =
                Model.normalized(r.optString("city")) + ":" + r.optString("kind") + ":" + address;
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        int count = 0,
            checks = 0;
        outer: for (List<JSONObject> bucket : buckets.values())
            for (int i = 0; i < bucket.size(); i++) for (int j = i + 1; j < bucket.size(); j++) {
                if (++checks > 50000 || count >= 50) break outer;
                JSONObject a = bucket.get(i),
                    b = bucket.get(j);
                if (
                    !a.optString("sameGroup").isBlank() &&
                    a.optString("sameGroup").equals(b.optString("sameGroup"))
                ) continue;
                String[] ids = { a.optString("id"), b.optString("id") };
                Arrays.sort(ids);
                String rule = "different:" + ids[0] + ":" + ids[1];
                if (db.state(rule, new JSONObject()).optBoolean("different")) continue;
                List<String> evidence = Dedupe.evidence(a, b, quote());
                if (evidence.isEmpty()) continue;
                count++;
                LinearLayout box = card();
                box.addView(
                    text(
                        a.optString("title") +
                            "\n" +
                            price(a) +
                            "\n\n" +
                            b.optString("title") +
                            "\n" +
                            price(b),
                        16,
                        INK
                    )
                );
                box.addView(
                    text(
                        String.join("\n", evidence) +
                            "\nЭто вероятное совпадение; объявления остаются отдельными.",
                        13,
                        MUTED
                    )
                );
                addButton(box, "Сравнить и подтвердить", () -> {
                    compare.clear();
                    compare.add(a.optString("id"));
                    compare.add(b.optString("id"));
                    comparison();
                });
                addButton(box, "Это разные объекты", () -> {
                    db.saveState(rule, Json.object("different", true));
                    toast("Указано: разные объекты");
                });
                content.addView(box);
            }
        if (count == 0) content.addView(
            text(
                "Доказательств для вероятных дублей пока недостаточно. Можно самостоятельно сравнить и подтвердить выбранные объявления.",
                15,
                MUTED
            )
        );
        if (count >= 50 || checks > 50000) content.addView(
            text(
                "Показана ограниченная порция совпадений; остальные исходные объявления сохранены.",
                13,
                MUTED
            )
        );
        dialog("Вероятные дубли", content).show();
    }

    private void appendGroup(LinearLayout content, JSONObject r) {
        String group = r.optString("sameGroup");
        if (group.isBlank()) return;
        content.addView(heading("Подтверждённые предложения"));
        for (JSONObject other : db.listings())
            if (other.optString("sameGroup").equals(group)) {
                content.addView(text(price(other) + " · " + other.optString("source"), 14, INK));
                addButton(content, "Открыть: " + other.optString("source"), () ->
                    detail(other.optString("id"))
                );
            }
        addButton(content, "Отменить объединение объекта", () -> {
            for (JSONObject other : db.listings())
                if (other.optString("sameGroup").equals(group)) db.local(
                    other.optString("id"),
                    "sameGroup",
                    ""
                );
            render();
            toast("Исходные объявления снова показаны отдельно");
        });
    }

    private void historicalFx(String id) {
        LinearLayout content = column();
        for (JSONObject h : db.history(id)) {
            String date = java.time.Instant.parse(h.optString("observedAt"))
                .atZone(Model.KYIV)
                .toLocalDate()
                .toString();
            JSONObject rates = db.state("fx:" + date, new JSONObject()).optJSONObject("rates");
            double rate = h.optString("currency").equals("UAH")
                ? 1
                : rates == null
                    ? Double.NaN
                    : rates.optDouble(h.optString("currency"), Double.NaN);
            content.addView(
                text(
                    shortDate(h.optString("observedAt")) +
                        " · " +
                        fmt(h.optDouble("price")) +
                        " " +
                        h.optString("currency") +
                        "\n" +
                        (Double.isFinite(rate)
                                ? fmt(h.optDouble("price") * rate) + " UAH · НБУ " + date
                                : "Курс на дату наблюдения недоступен"),
                    14,
                    INK
                )
            );
        }
        dialog("Цена и курс на дату наблюдения", content).show();
    }

    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("pendingPhotoListing", pendingPhotoListing);
        state.putString("activeSavedSearchId",activeSavedSearchId);
        state.putBoolean("favoriteMapOpened",favoriteMapOpened);
        state.putString("workspaceTab",tab);
        state.putString("pendingBrowserUrl",pendingBrowserUrl);
        state.putString("openedListingId",openedListingId);
    }

    private void visitTools(LinearLayout content, JSONObject r) {
        String id = r.optString("id");
        content.addView(heading("Мой просмотр"));
        Switch visited = new Switch(this);
        visited.setText("Уже осмотрел объект");
        visited.setChecked(r.optBoolean("visited"));
        visited.setOnCheckedChangeListener((v, on) -> db.local(id, "visited", on));
        content.addView(visited);
        JSONObject checklist = r.optJSONObject("checklist");
        if (checklist == null) checklist = new JSONObject();
        final JSONObject marks = checklist;
        for (String label : List.of(
            "Задал вопросы о документах",
            "Осмотрел стены и окна",
            "Проверил отопление и воду",
            "Уточнил текущие расходы",
            "Сохранил вопросы продавцу"
        )) {
            CheckBox item = new CheckBox(this);
            item.setText(label);
            item.setChecked(marks.optBoolean(label));
            item.setOnCheckedChangeListener((v, on) -> {
                Json.put(marks, label, on);
                db.local(id, "checklist", marks);
            });
            content.addView(item);
        }
        JSONArray photos = db
            .state("photos:" + id, Json.object("items", new JSONArray()))
            .optJSONArray("items");
        for (int i = 0; i < Math.min(3, photos.length()); i++) {
            android.graphics.Bitmap bitmap = Photos.bitmap(photos.optString(i));
            if (bitmap != null) {
                ImageView image = new ImageView(this);
                image.setContentDescription("Моя фотография объекта");
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setImageBitmap(bitmap);
                content.addView(image, new LinearLayout.LayoutParams(-1, dp(180)));
            }
        }
        addButton(content, "Добавить мою фотографию", () -> {
            pendingPhotoListing = id;
            startActivityForResult(
                new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).setType("image/*"),
                23
            );
        });
    }
}

package ua.domtrack.mobile;

import android.content.Context;
import android.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import org.json.*;

final class Scanner {

    static final AtomicBoolean BUSY = new AtomicBoolean();
    private final Context context;
    private final Database db;
    private final Network net = new Network();

    Scanner(Context c, Database d) {
        context = c.getApplicationContext();
        db = d;
    }

    int scan(boolean deep, BooleanSupplier stop, Consumer<String> progress) throws Exception {
        return scan(deep, stop, progress, null);
    }

    static List<Sources.Section> sectionsFor(List<JSONObject> saved, JSONObject requested) {
        List<Sources.Section> sections = new ArrayList<>();
        List<JSONObject> searches = requested == null
            ? saved
            : List.of(Json.object("enabled", true, "filters", requested));
        for (JSONObject search : searches) {
            if (!search.optBoolean("enabled", true)) continue;
            JSONObject filters = search.optJSONObject("filters");
            if (filters == null) continue;
            for (Sources.Section section : Sources.sections(
                filters.optString("city"),
                filters.optString("kind", "both")
            ))
                if (sections.stream().noneMatch(s -> s.key().equals(section.key()))) sections.add(
                    section
                );
        }
        return sections;
    }

    int scan(boolean deep, BooleanSupplier stop, Consumer<String> progress, JSONObject requested)
        throws Exception {
        if (!BUSY.compareAndSet(false, true)) return 0;
        long started = System.currentTimeMillis();
        int imported = 0,
            attempted = 0;
        JSONArray failures = new JSONArray();
        try {
            List<Sources.Section> sections = sectionsFor(db.searches(), requested);
            if (
                sections.isEmpty() &&
                db
                    .listings()
                    .stream()
                    .noneMatch(r -> r.optJSONObject("watch") != null)
            ) return 0;
            try {
                Fx.get(db, net, Model.date());
            } catch (Exception e) {
                failures.put("Не удалось получить сегодняшний курс НБУ; исходные цены сохранены");
            }
            if (
                deep &&
                !sections.isEmpty() &&
                sections
                    .stream()
                    .allMatch(s ->
                        db.state("section:" + s.key(), new JSONObject()).optBoolean("complete")
                    )
            ) {
                for (Sources.Section section : sections) {
                    JSONObject state = db.state("section:" + section.key(), new JSONObject());
                    Json.put(state, "complete", false);
                    Json.put(state, "nextPage", 1);
                    Json.put(state, "frontNext", true);
                    db.saveState("section:" + section.key(), state);
                }
            }
            boolean metered = context
                .getSystemService(ConnectivityManager.class)
                .isActiveNetworkMetered();
            JSONObject schedule = db.state("scanSchedule", new JSONObject());
            int cursor = schedule.optInt("cursor", 0),
                budget = deep ? Integer.MAX_VALUE : metered ? 2 : 4;
            long limit = deep ? Long.MAX_VALUE : 55000L;
            Set<String> failed = new HashSet<>();
            while (
                !sections.isEmpty() &&
                attempted < budget &&
                !stop.getAsBoolean() &&
                System.currentTimeMillis() - started < limit
            ) {
                Sources.Section section = sections.get(Math.floorMod(cursor++, sections.size()));
                JSONObject state = db.state("section:" + section.key(), new JSONObject());
                if (failed.contains(section.key())) {
                    if (failed.size() == sections.size()) break;
                    continue;
                }
                if (deep && state.optBoolean("complete")) {
                    if (
                        sections
                            .stream()
                            .allMatch(s ->
                                db
                                    .state("section:" + s.key(), new JSONObject())
                                    .optBoolean("complete")
                            )
                    ) break;
                    continue;
                }
                if (state.optLong("retryAfter") > System.currentTimeMillis()) {
                    failed.add(section.key());
                    continue;
                }
                // Alternate front-page updates and persisted catalog continuation. Every section gets a turn.
                boolean front = !deep && state.optBoolean("frontNext", true);
                int page = front ? 1 : state.optInt("nextPage", 1);
                if (page > 10000) {
                    failed.add(section.key());
                    Json.put(
                        state,
                        "error",
                        "Достигнут защитный предел страниц; обход не завершён"
                    );
                    db.saveState("section:" + section.key(), state);
                    continue;
                }
                if (progress != null) progress.accept(
                    section.source +
                        " · " +
                        section.city +
                        " · " +
                        (section.kind.equals("house") ? "дома" : "квартиры") +
                        " · страница " +
                        page
                );
                attempted++;
                try {
                    String url = section.page(page);
                    if (!net.robotsAllowed(url, db)) throw new IllegalArgumentException(
                        "robots.txt ограничивает автоматическую загрузку этого раздела"
                    );
                    JSONObject cache = db.state("cache:" + Model.hash(url), new JSONObject());
                    Network.Page response = net.get(
                        url,
                        cache.optString("etag"),
                        cache.optString("modified")
                    );
                    if (response.status == 304) {
                        if (!cache.optBoolean("observed")) throw new IllegalArgumentException(
                            "Нет исходного наблюдения для ответа 304"
                        );
                        if (!front) {
                            Json.put(state, "nextPage", cache.optBoolean("end") ? 1 : page + 1);
                            Json.put(state, "complete", cache.optBoolean("end"));
                            Json.put(state, "fingerprint", cache.optString("fingerprint"));
                        }
                        Json.put(state, "checkedAt", Model.now());
                        Json.put(state, "frontNext", !front);
                        db.saveState("section:" + section.key(), state);
                        continue;
                    }
                    Sources.Result result = Sources.parse(
                        response.body,
                        response.url,
                        section,
                        page
                    );
                    if (
                        page > 1 && state.optString("fingerprint").equals(result.fingerprint)
                    ) throw new IllegalArgumentException(
                        "Сайт повторил предыдущую страницу; обход приостановлен"
                    );
                    for (JSONObject record : result.records) {
                        Json.put(record, "catalogUrl", url);
                        Json.put(record, "catalogPage", page);
                    }
                    boolean baseline = cache.optBoolean("observed", false);
                    imported += db.ingest(result.records, baseline, true);
                    db.saveState(
                        "cache:" + Model.hash(url),
                        Json.object(
                            "observed",
                            true,
                            "etag",
                            response.etag,
                            "modified",
                            response.modified,
                            "end",
                            result.end,
                            "fingerprint",
                            result.fingerprint
                        )
                    );
                    Json.put(state, "checkedAt", Model.now());
                    Json.put(state, "lastPage", page);
                    Json.put(state, "lastCount", result.records.size());
                    Json.put(state, "error", "");
                    Json.put(state, "failures", 0);
                    Json.put(state, "retryAfter", 0);
                    Json.put(state, "frontNext", !front);
                    if (!front || (page == 1 && state.optInt("nextPage", 1) == 1)) {
                        Json.put(state, "nextPage", result.end ? 1 : page + 1);
                        Json.put(state, "complete", result.end);
                        Json.put(state, "fingerprint", result.fingerprint);
                    }
                    db.saveState("section:" + section.key(), state);
                    if (!deep && state.optBoolean("complete")) {
                        Json.put(state, "frontNext", true);
                        db.saveState("section:" + section.key(), state);
                    }
                } catch (Exception e) {
                    failed.add(section.key());
                    int n = state.optInt("failures", 0) + 1;
                    Json.put(state, "error", safe(e));
                    Json.put(state, "failures", n);
                    Json.put(
                        state,
                        "retryAfter",
                        System.currentTimeMillis() +
                            Math.min(21600000L, 900000L * (1L << Math.min(4, n - 1)))
                    );
                    db.saveState("section:" + section.key(), state);
                    failures.put(section.source + ": " + safe(e));
                }
                db.saveState("scanSchedule", Json.object("cursor", cursor));
                if (!stop.getAsBoolean()) Thread.sleep(2000);
            }
            // Individually watched structured pages are independent of the PC and catalog order.
            if (!stop.getAsBoolean() && System.currentTimeMillis() - started < limit) {
                int checked = 0;
                List<JSONObject> monitored = db
                    .listings()
                    .stream()
                    .filter(
                        o ->
                            o.optJSONObject("watch") != null &&
                            o.optJSONObject("watch").optBoolean("enabled", true)
                    )
                    .toList();
                int watchCursor = db.state("watchCursor", new JSONObject()).optInt("next", 0);
                Set<String> seenWatchPages = new HashSet<>();
                for (int watchIndex = 0; watchIndex < monitored.size(); watchIndex++) {
                    JSONObject r = monitored.get(
                        Math.floorMod(watchCursor + watchIndex, monitored.size())
                    );
                    if (
                        checked >= 2 ||
                        stop.getAsBoolean() ||
                        System.currentTimeMillis() - started >= limit
                    ) break;
                    db.saveState("watchCursor", Json.object("next", watchCursor + watchIndex + 1));
                    JSONObject watch = r.optJSONObject("watch");
                    if (watch == null || !watch.optBoolean("enabled", true)) continue;
                    try {
                        String url = r.optString("catalogUrl", r.optString("url"));
                        if (!seenWatchPages.add(url)) continue;
                        checked++;
                        if (net.robotsAllowed(url, db)) {
                            Network.Page response = net.get(url);
                            if (r.optBoolean("structured")) imported += db.ingest(
                                Sources.structured(response.body, response.url),
                                false,
                                true
                            );
                            else {
                                Sources.Section source = Sources.sections(
                                    r.optString("city"),
                                    r.optString("kind")
                                )
                                    .stream()
                                    .filter(s -> s.source.equals(r.optString("source")))
                                    .findFirst()
                                    .orElseThrow(() ->
                                        new IllegalArgumentException(
                                            "Нужен адаптер отдельной страницы"
                                        )
                                    );
                                imported += db.ingest(
                                    Sources.parse(
                                        response.body,
                                        response.url,
                                        source,
                                        r.optInt("catalogPage", 1)
                                    ).records,
                                    false,
                                    true
                                );
                            }
                        }
                    } catch (Exception e) {
                        failures.put("Наблюдение: " + safe(e));
                    }
                }
            }
            Alerts.deliver(context, db);
            db.saveState(
                "lastCheck",
                Json.object(
                    "at",
                    Model.now(),
                    "durationMs",
                    System.currentTimeMillis() - started,
                    "pages",
                    attempted,
                    "imported",
                    imported,
                    "errors",
                    failures,
                    "stopped",
                    stop.getAsBoolean(),
                    "mode",
                    deep ? "full" : "background"
                )
            );
            return imported;
        } finally {
            BUSY.set(false);
        }
    }

    static String safe(Exception e) {
        String s = Objects.toString(e.getMessage(), "Ошибка загрузки");
        if (s.contains("api_key") || s.contains("https://")) return "Не удалось загрузить источник";
        return s.substring(0, Math.min(240, s.length()));
    }

    int importUrl(String url) throws Exception {
        Network.safe(url);
        if (!net.robotsAllowed(url, db)) throw new IllegalArgumentException(
            "Источник ограничивает автоматическую загрузку"
        );
        List<JSONObject> rows = Sources.structured(net.get(url).body, url);
        for (JSONObject r : rows) Json.put(r, "structured", true);
        return db.ingest(rows, false);
    }
}

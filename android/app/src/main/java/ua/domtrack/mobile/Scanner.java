package ua.domtrack.mobile;

import android.content.Context;
import android.net.ConnectivityManager;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.json.*;

final class Scanner {

    static final AtomicBoolean BUSY = new AtomicBoolean();
    private final Context context;
    private final Database db;
    private final Network net = new Network();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private JSONObject lastReport = new JSONObject();

    JSONObject report() { return Json.copy(lastReport); }

    static JSONObject manualStart(JSONObject previous) {
        return previous.optBoolean("complete") ? Json.object("nextPage", 1) : Json.copy(previous);
    }

    Scanner(Context c, Database d) {
        context = c.getApplicationContext();
        db = d;
    }

    void cancel() {
        cancelled.set(true);
        net.cancel();
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
            for (Sources.Section s : Sources.sections(
                filters.optString("city"),
                filters.optString("kind", "both")
            ))
                if (sections.stream().noneMatch(old -> old.key().equals(s.key()))) sections.add(s);
        }
        return sections;
    }

    int scan(boolean deep, BooleanSupplier stop, Consumer<String> progress) throws Exception {
        return scan(deep, stop, progress, null);
    }

    int scan(boolean deep, BooleanSupplier stop, Consumer<String> progress, JSONObject requested)
        throws Exception {
        lastReport = new JSONObject();
        if (!BUSY.compareAndSet(false, true)) return 0;
        long started = System.currentTimeMillis();
        AtomicInteger pages = new AtomicInteger(),
            imported = new AtomicInteger(),
            loadedPages = new AtomicInteger(), watchChecks = new AtomicInteger();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        BooleanSupplier halted = () ->
            cancelled.get() || stop.getAsBoolean() || Thread.currentThread().isInterrupted();
        try {
            List<Sources.Section> sections = sectionsFor(db.searches(), requested);
            if (
                sections.isEmpty() &&
                db
                    .listings()
                    .stream()
                    .noneMatch(
                        r ->
                            r.optJSONObject("watch") != null &&
                            r.optJSONObject("watch").optBoolean("enabled", true)
                    )
            ) return 0;
            if (halted.getAsBoolean()) return 0;
            try {
                Fx.get(db, net, Model.date());
            } catch (Exception e) {
                failures.add("Курс НБУ недоступен; исходные цены сохранены");
            }
            // Reserve a watch slot before slow catalogs can consume the whole short-run budget.
            if (!deep && !halted.getAsBoolean()) watchChecks.set(checkWatches(
                halted, failures, imported, started + 55000, 1));
            if (deep && !sections.isEmpty()) {
                // Persist one cursor per catalog. An interrupted sweep resumes at its last committed page.
                for (Sources.Section s : sections)
                    db.saveState("section:" + s.key(), manualStart(state(s)));
                Map<String, List<Sources.Section>> hosts = new LinkedHashMap<>();
                for (Sources.Section s : sections)
                    hosts
                        .computeIfAbsent(URI.create(s.base).getHost(), k -> new ArrayList<>())
                        .add(s);
                ExecutorService pool = Executors.newFixedThreadPool(hosts.size());
                try {
                    List<Future<?>> tasks = new ArrayList<>();
                    for (List<Sources.Section> host : hosts.values())
                        tasks.add(
                            pool.submit(() -> {
                                Set<String> failed = new HashSet<>();
                                Set<String> frontChecked = new HashSet<>();
                                while (!halted.getAsBoolean()) {
                                    boolean work = false;
                                    for (Sources.Section s : host) {
                                        if (halted.getAsBoolean()) break;
                                        if (
                                            failed.contains(s.key()) ||
                                            state(s).optBoolean("complete")
                                        ) continue;
                                        work = true;
                                        boolean front =
                                            frontChecked.add(s.key()) &&
                                            state(s).optInt("nextPage", 1) > 1;
                                        int page = front ? 1 : state(s).optInt("nextPage", 1);
                                        try {
                                            if (page > 10000) throw new IllegalArgumentException(
                                                "Защитный предел страниц; обход не завершён"
                                            );
                                            pages.incrementAndGet();
                                            imported.addAndGet(load(s, page, front));
                                            loadedPages.incrementAndGet();
                                        } catch (Exception e) {
                                            if (halted.getAsBoolean()) break;
                                            failed.add(s.key());
                                            failure(s, e, failures);
                                        }
                                        if (progress != null) progress.accept(
                                            "Страниц: " +
                                                pages.get() +
                                                " · записей: " +
                                                imported.get() +
                                                " · " +
                                                s.source +
                                                " · " +
                                                (s.kind.equals("house") ? "дома" : "квартиры") +
                                                " · стр. " +
                                                page
                                        );
                                    }
                                    if (!work) break;
                                }
                            })
                        );
                    for (Future<?> task : tasks) task.get();
                } finally {
                    pool.shutdownNow();
                }
            } else if (!deep && !sections.isEmpty()) {
                boolean metered = context
                    .getSystemService(ConnectivityManager.class)
                    .isActiveNetworkMetered();
                int budget = (metered ? 2 : 4) - Math.min(1, watchChecks.get()),
                    cursor = db.state("scanSchedule", new JSONObject()).optInt("cursor");
                Set<String> failed = new HashSet<>();
                while (
                    pages.get() < budget &&
                    !halted.getAsBoolean() &&
                    System.currentTimeMillis() - started < 55000 &&
                    failed.size() < sections.size()
                ) {
                    Sources.Section s = sections.get(Math.floorMod(cursor++, sections.size()));
                    JSONObject state = state(s);
                    if (failed.contains(s.key())) continue;
                    if (state.optLong("retryAfter") > System.currentTimeMillis()) {
                        failed.add(s.key());
                        continue;
                    }
                    boolean front = state.optBoolean("frontNext", true);
                    try {
                        pages.incrementAndGet();
                        imported.addAndGet(load(s, front ? 1 : state.optInt("nextPage", 1), front));
                        loadedPages.incrementAndGet();
                    } catch (Exception e) {
                        if (halted.getAsBoolean()) break;
                        failed.add(s.key());
                        failure(s, e, failures);
                    }
                    db.saveState("scanSchedule", Json.object("cursor", cursor));
                }
            }
            if (deep && !halted.getAsBoolean()) watchChecks.set(checkWatches(
                halted,
                failures,
                imported,
                Long.MAX_VALUE, 2
            ));
            Alerts.deliver(context, db);
            boolean complete =
                !sections.isEmpty() &&
                sections.stream().allMatch(s -> state(s).optBoolean("complete"));
            lastReport = Json.object(
                    "at",
                    Model.now(),
                    "durationMs",
                    System.currentTimeMillis() - started,
                    "pages",
                    pages.get(),
                    "loadedPages", loadedPages.get(),
                    "watchChecks", watchChecks.get(),
                    "imported",
                    imported.get(),
                    "errors",
                    Json.array(failures),
                    "stopped",
                    halted.getAsBoolean(),
                    "complete",
                    complete,
                    "mode",
                    deep ? "full" : "background"
                );
            if (loadedPages.get() == 0 && watchChecks.get() == 0 && failures.isEmpty()) {
                long now = System.currentTimeMillis();
                long retry = sections.stream().mapToLong(s -> state(s).optLong("retryAfter"))
                    .filter(at -> at > now).min().orElse(0L);
                Json.put(lastReport, "nextRetryAtMillis", retry);
            }
            db.saveState("lastCheck", lastReport);
            return imported.get();
        } finally {
            BUSY.set(false);
        }
    }

    private JSONObject state(Sources.Section s) {
        return db.state("section:" + s.key(), new JSONObject());
    }

    private void failure(Sources.Section s, Exception e, List<String> failures) {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) return;
        JSONObject state = state(s);
        int n = state.optInt("failures") + 1;
        Json.put(state, "error", safe(e));
        Json.put(state, "failures", n);
        Json.put(
            state,
            "retryAfter",
            System.currentTimeMillis() + Math.min(21600000L, 900000L * (1L << Math.min(4, n - 1)))
        );
        db.saveState("section:" + s.key(), state);
        failures.add(s.source + ": " + safe(e));
    }

    private int load(Sources.Section section, int page, boolean front) throws Exception {
        String url = section.page(page);
        if (cancelled.get()) throw new InterruptedException();
        if (!net.robotsAllowed(url, db)) throw new IllegalArgumentException(
            "robots.txt ограничивает загрузку раздела"
        );
        JSONObject state = state(section),
            cache = db.state("cache:" + Model.hash(url), new JSONObject());
        Network.Page response = net.get(url, cache.optString("etag"), cache.optString("modified"));
        int count = 0;
        boolean end;
        String fingerprint;
        if (response.status == 304) {
            if (!cache.optBoolean("observed")) throw new IllegalArgumentException(
                "Нет исходного наблюдения для ответа 304"
            );
            end = cache.optBoolean("end");
            fingerprint = cache.optString("fingerprint");
        } else {
            Sources.Result result = Sources.parse(response.body, response.url, section, page);
            if (
                page > 1 &&
                !result.fingerprint.isBlank() &&
                state.optString("fingerprint").equals(result.fingerprint)
            ) throw new IllegalArgumentException(
                "Сайт повторил предыдущую страницу; обход приостановлен"
            );
            for (JSONObject r : result.records) {
                Json.put(r, "catalogUrl", url);
                Json.put(r, "catalogPage", page);
            }
            count = db.ingest(result.records, cache.optBoolean("observed"), true);
            end = result.end;
            fingerprint = result.fingerprint;
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
                    end,
                    "fingerprint",
                    fingerprint
                )
            );
            Json.put(state, "lastCount", result.records.size());
        }
        Json.put(state, "checkedAt", Model.now());
        Json.put(state, "lastPage", page);
        Json.put(state, "error", "");
        Json.put(state, "failures", 0);
        Json.put(state, "retryAfter", 0);
        Json.put(state, "frontNext", !front);
        if (!front || state.optInt("nextPage", 1) == 1) {
            Json.put(state, "nextPage", end ? 1 : page + 1);
            Json.put(state, "complete", end);
            Json.put(state, "fingerprint", fingerprint);
        }
        db.saveState("section:" + section.key(), state);
        return count;
    }

    private int checkWatches(
        BooleanSupplier stop,
        List<String> failures,
        AtomicInteger imported,
        long until, int limit
    ) {
        List<JSONObject> rows = db
            .listings()
            .stream()
            .filter(
                r ->
                    r.optJSONObject("watch") != null &&
                    r.optJSONObject("watch").optBoolean("enabled", true)
            )
            .toList();
        int cursor = db.state("watchCursor", new JSONObject()).optInt("next"),
            checked = 0, successful = 0;
        Set<String> seen = new HashSet<>();
        for (
            int i = 0;
            i < rows.size() &&
            checked < limit &&
            !stop.getAsBoolean() &&
            System.currentTimeMillis() < until;
            i++
        ) {
            JSONObject r = rows.get(Math.floorMod(cursor + i, rows.size()));
            db.saveState("watchCursor", Json.object("next", cursor + i + 1));
            try {
                String url = r.optBoolean("structured")
                    ? r.optString("url")
                    : r.optString("catalogUrl", r.optString("url"));
                if (!seen.add(url)) continue;
                checked++;
                if (!net.robotsAllowed(url, db)) {
                    failures.add("Наблюдение: robots.txt ограничивает загрузку");
                    continue;
                }
                Network.Page response = net.get(url);
                if (r.optBoolean("structured")) imported.addAndGet(
                    db.ingest(Sources.structured(response.body, response.url), false, true)
                );
                else {
                    Sources.Section s = Sources.sections(r.optString("city"), r.optString("kind"))
                        .stream()
                        .filter(x -> x.source.equals(r.optString("source")))
                        .findFirst()
                        .orElseThrow();
                    imported.addAndGet(
                        db.ingest(
                            Sources.parse(
                                response.body,
                                response.url,
                                s,
                                r.optInt("catalogPage", 1)
                            ).records,
                            false,
                            true
                        )
                    );
                }
                successful++;
            } catch (Exception e) {
                if (stop.getAsBoolean() || Thread.currentThread().isInterrupted()) break;
                failures.add("Наблюдение: " + safe(e));
            }
        }
        return successful;
    }

    int coordinates(JSONObject filters, BooleanSupplier stop, Consumer<String> progress)
        throws Exception {
        if (!BUSY.compareAndSet(false, true)) return 0;
        try {
            return Coordinates.load(
                context,
                db,
                net,
                filters,
                () -> cancelled.get() || stop.getAsBoolean(),
                progress
            );
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

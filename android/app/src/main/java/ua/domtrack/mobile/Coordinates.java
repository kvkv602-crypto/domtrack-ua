package ua.domtrack.mobile;

import android.content.Context;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import org.json.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;

/** Optional detail-page enrichment; never geocodes or guesses a property position. */
final class Coordinates {

    static JSONObject parse(String html, String url) {
        Document doc = Jsoup.parse(html, url);
        List<JSONObject> ria = RiaPage.parse(doc, url);
        if (!ria.isEmpty() && ria.get(0).has("latitude")) return ria.get(0);
        Element canonical = doc.selectFirst("head link[rel=canonical]");
        if (canonical == null || !canonical.absUrl("href").equals(url)) return new JSONObject();
        List<JSONObject> candidates = new ArrayList<>();
        for (Element script : doc.select("script[type=application/ld+json]"))
            try {
                collect(new JSONTokener(script.data()).nextValue(), url, candidates);
            } catch (Exception ignored) {}
        return candidates.size() == 1 ? candidates.get(0) : new JSONObject();
    }

    private static void collect(Object value, String url, List<JSONObject> out) {
        if (value instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) collect(array.opt(i), url, out);
            return;
        }
        if (!(value instanceof JSONObject node)) return;
        if (node.has("@graph")) collect(node.opt("@graph"), url, out);
        if (
            !List.of("Apartment", "House", "SingleFamilyResidence", "Residence").contains(
                node.optString("@type")
            )
        ) return;
        String identity = node.optString("url", node.optString("@id"));
        if (!identity.isBlank() && !identity.equals(url)) return;
        JSONObject record = new JSONObject();
        Sources.walkLd(node, record, true);
        if (record.has("latitude") && record.has("longitude")) out.add(record);
    }

    static int load(
        Context context,
        Database db,
        Network net,
        JSONObject filters,
        BooleanSupplier stop,
        Consumer<String> progress
    ) throws Exception {
        filters = Json.copy(filters);
        filters.remove("polygon");
        JSONObject quote = db.state("fx:" + Model.date(), new JSONObject());
        Map<String, List<JSONObject>> hosts = new LinkedHashMap<>();
        for (JSONObject record : db.listings()) {
            if (
                record.optBoolean("hidden") ||
                !Model.matches(record, filters, quote) ||
                (record.has("latitude") && record.has("longitude"))
            ) continue;
            if (
                System.currentTimeMillis() - record.optLong("coordinatesCheckedAt") < 86400000L
            ) continue;
            String host = URI.create(record.optString("url")).getHost();
            hosts.computeIfAbsent(host, k -> new ArrayList<>()).add(record);
        }
        if (hosts.isEmpty()) return 0;
        AtomicInteger checked = new AtomicInteger(),
            found = new AtomicInteger();
        int total = hosts.values().stream().mapToInt(List::size).sum();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, hosts.size()));
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (List<JSONObject> rows : hosts.values())
                tasks.add(
                    pool.submit(() -> {
                        for (JSONObject record : rows) {
                            if (
                                stop.getAsBoolean() || Thread.currentThread().isInterrupted()
                            ) return;
                            String url = record.optString("url");
                            try {
                                if (!net.robotsAllowed(url, db)) continue;
                                Network.Page page = net.get(url);
                                JSONObject geo = parse(page.body, page.url);
                                if (geo.has("latitude") && geo.has("longitude")) {
                                    db.location(record.optString("id"), geo);
                                    found.incrementAndGet();
                                }
                                db.locationAttempt(record.optString("id"));
                            } catch (Exception ignored) {
                                /* A temporary error remains retryable; no invented location. */
                            }
                            if (progress != null) progress.accept(
                                "Координаты: проверено " +
                                    checked.incrementAndGet() +
                                    " / " +
                                    total +
                                    " · найдено " +
                                    found.get()
                            );
                        }
                    })
                );
            for (Future<?> task : tasks) task.get();
        } finally {
            pool.shutdownNow();
        }
        return found.get();
    }
}

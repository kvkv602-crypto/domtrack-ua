package ua.domtrack.mobile;

import android.content.Context;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

final class SemanticIndex {

    static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    static String text(JSONObject r) {
        return (r.optString("title") + ". " + r.optString("description")).replaceAll(
            "\\s+",
            " "
        ).trim();
    }

    static String key(JSONObject r) {
        return Model.hash(SemanticModel.VERSION + ":" + text(r));
    }

    static String bucket(JSONObject r) {
        String address = Dedupe.address(r);
        if (
            address.isBlank() ||
            !r.has("rooms") ||
            !r.has("area") ||
            (r.optString("kind").equals("apartment") && !r.has("floor"))
        ) return "";
        return (
            Bilingual.city(r.optString("city")) +
            ":" +
            r.optString("kind") +
            ":" +
            address +
            ":" +
            r.optInt("rooms") +
            ":" +
            r.optInt("floor", -1)
        );
    }

    static boolean physical(JSONObject a, JSONObject b) {
        if (bucket(a).isBlank() || !bucket(a).equals(bucket(b))) return false;
        if (
            a.has("floorsTotal") &&
            b.has("floorsTotal") &&
            a.optInt("floorsTotal") != b.optInt("floorsTotal")
        ) return false;
        double x = a.optDouble("area"),
            y = b.optDouble("area");
        return x > 0 && y > 0 && Math.abs(x - y) <= Math.max(.5, Math.min(x, y) * .005);
    }

    static String pairKey(String a, String b) {
        return a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a;
    }

    static void request(Context context, Database db) {
        if (
            !db.state("settings", new JSONObject()).optBoolean("localAi", true) ||
            !RUNNING.compareAndSet(false, true)
        ) return;
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                build(app, db);
            } catch (Exception | LinkageError error) {
                db.saveState(
                    "aiStatus",
                    Json.object(
                        "message",
                        "ИИ недоступен: " +
                            (error instanceof Exception
                                    ? Scanner.safe((Exception) error)
                                    : "не удалось загрузить библиотеку"),
                        "at",
                        Model.now()
                    )
                );
            } finally {
                RUNNING.set(false);
            }
        });
    }

    static void build(Context context, Database db) throws Exception {
        build(context, db, () -> false);
    }

    static synchronized void build(
        Context context,
        Database db,
        java.util.function.BooleanSupplier stop
    ) throws Exception {
        Map<String, List<JSONObject>> buckets = new LinkedHashMap<>();
        for (JSONObject r : db.listings()) {
            String key = bucket(r);
            if (!key.isBlank()) buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        JSONArray pairs = new JSONArray();
        int processed = 0;
        if (
            buckets
                .values()
                .stream()
                .noneMatch(group -> group.size() > 1)
        ) {
            db.saveState("duplicateIndex", Json.object("pairs", pairs));
            return;
        }
        try (SemanticModel model = new SemanticModel(context)) {
            Map<String, float[]> vectors = new HashMap<>();
            for (List<JSONObject> group : buckets.values()) {
                if (group.size() < 2) continue;
                for (int i = 0; i < group.size(); i++) for (int j = i + 1; j < group.size(); j++) {
                    if (stop.getAsBoolean() || Thread.currentThread().isInterrupted()) return;
                    JSONObject a = group.get(i),
                        b = group.get(j);
                    String idA = a.optString("id"),
                        idB = b.optString("id");
                    if (
                        !physical(a, b) ||
                        db
                            .state("different:" + pairKey(idA, idB), new JSONObject())
                            .optBoolean("different")
                    ) continue;
                    if (text(a).length() < 50 || text(b).length() < 50) continue;
                    float[] x = vector(a, model, db, vectors),
                        y = vector(b, model, db, vectors);
                    double score = SemanticModel.cosine(x, y);
                    // This score is similarity, not a claimed probability of object identity.
                    // Close price supports a strong match; a large price change does not disprove it.
                    double ap = a.optDouble("price"),
                        bp = b.optDouble("price");
                    JSONObject quote = db.state("fx:" + Model.date(), new JSONObject());
                    if (!a.optString("currency").equals(b.optString("currency"))) {
                        ap = Model.convert(ap, a.optString("currency"), "UAH", quote);
                        bp = Model.convert(bp, b.optString("currency"), "UAH", quote);
                    }
                    double gap = Double.isFinite(ap) && Double.isFinite(bp) && Math.min(ap, bp) > 0
                        ? Math.abs(ap - bp) / Math.min(ap, bp)
                        : Double.NaN;
                    if (score >= .94 || (score >= .89 && gap <= .15)) pairs.put(
                        Json.object(
                            "a",
                            idA,
                            "b",
                            idB,
                            "hashA",
                            key(a),
                            "hashB",
                            key(b),
                            "score",
                            score,
                            "evidence",
                            "Многоязычная нейросеть: близкие описания; совпадают адрес и характеристики"
                        )
                    );
                    if (++processed % 20 == 0) db.saveState(
                        "aiStatus",
                        Json.object("message", "ИИ: проверено пар " + processed, "at", Model.now())
                    );
                }
                vectors.clear();
            }
        }
        db.saveState(
            "duplicateIndex",
            Json.object("pairs", pairs, "model", SemanticModel.VERSION, "at", Model.now())
        );
        db.saveState(
            "aiStatus",
            Json.object(
                "message",
                "ИИ: проверено пар " + processed + " · вероятных совпадений " + pairs.length(),
                "at",
                Model.now()
            )
        );
    }

    private static float[] vector(
        JSONObject r,
        SemanticModel model,
        Database db,
        Map<String, float[]> cache
    ) throws Exception {
        String key = key(r);
        float[] result = cache.get(key);
        if (result == null) result = db.embedding(key);
        if (result == null) {
            result = model.embed(text(r));
            db.saveEmbedding(key, result);
        }
        cache.put(key, result);
        return result;
    }

    static List<JSONObject> display(
        List<JSONObject> rows,
        JSONObject quote,
        String currency,
        JSONObject index
    ) {
        List<JSONObject> confirmed = Dedupe.collapsed(rows, quote, currency);
        Map<String, JSONObject> byId = new LinkedHashMap<>();
        for (JSONObject r : confirmed) byId.put(r.optString("id"), r);
        Map<String, JSONObject> evidence = new HashMap<>();
        for (JSONObject pair : Json.objects(index.optJSONArray("pairs"))) {
            JSONObject a = byId.get(pair.optString("a")),
                b = byId.get(pair.optString("b"));
            if (
                a != null &&
                b != null &&
                key(a).equals(pair.optString("hashA")) &&
                key(b).equals(pair.optString("hashB"))
            ) evidence.put(pairKey(a.optString("id"), b.optString("id")), pair);
        }
        List<JSONObject> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (JSONObject a : confirmed) {
            String id = a.optString("id");
            if (!used.add(id)) continue;
            List<JSONObject> group = new ArrayList<>();
            group.add(a);
            for (JSONObject b : confirmed) {
                if (
                    used.contains(b.optString("id")) || !b.optString("sameGroup").isBlank()
                ) continue;
                if (
                    group
                        .stream()
                        .allMatch(r ->
                            evidence.containsKey(pairKey(r.optString("id"), b.optString("id")))
                        )
                ) {
                    group.add(b);
                    used.add(b.optString("id"));
                }
            }
            if (group.size() == 1) {
                out.add(a);
                continue;
            }
            String temporary = "display-" + id;
            List<JSONObject> copies = new ArrayList<>();
            for (JSONObject r : group) {
                JSONObject copy = Json.copy(r);
                Json.put(copy, "sameGroup", temporary);
                copies.add(copy);
            }
            List<JSONObject> collapsed = Dedupe.collapsed(copies, quote, currency);
            if (collapsed.size() != 1) {
                out.addAll(group);
                continue;
            }
            JSONObject display = Json.copy(byId.get(collapsed.get(0).optString("id")));
            Json.put(display, "probableAlternatives", Json.array(group));
            out.add(display);
        }
        return out;
    }
}

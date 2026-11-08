package ua.domtrack.mobile;

import android.content.Context;
import java.net.URI;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.*;
import org.json.*;

final class Ria {

    private final Database db;
    private final Network net;
    private final String key, quotaKey;
    private static final Object REQUEST_LOCK = new Object();
    private static final Pattern LISTING_PATH = Pattern.compile(
        "/(?:uk/)?realty-(?:[a-z0-9]+-)*([1-9][0-9]{0,11})\\.html"
    );
    private static final String API_IDENTITY = "riaApiIdentity";

    // JSON imports cannot manufacture this in-memory proof that normalization saw an API payload.
    private static final class ApiObservation extends JSONObject {
        final String externalId, city, kind;
        ApiObservation(JSONObject row) {
            externalId=row.optString("externalId"); city=row.optString("city"); kind=row.optString("kind");
            for (String key : Json.keys(row)) Json.put(this, key, row.opt(key));
        }
    }

    Ria(Context c, Database d) throws Exception {
        this(c, d, new Network());
    }

    Ria(Context c, Database d, Network network) throws Exception {
        db = d;
        net = network;
        key = new Secrets(c).get();
        if (key.isBlank()) throw new IllegalArgumentException("Сначала сохрани API-ключ RIA");
        quotaKey = "ria-quota:" + Model.hash(key);
    }

    private Object request(String path, Map<String, String> query) throws Exception {
        return request(path, query, () -> false, Long.MAX_VALUE);
    }

    private static void running(BooleanSupplier stop, long until) throws InterruptedException {
        if (stop.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedException(
            "Проверка RIA остановлена"
        );
        if (System.currentTimeMillis() >= until) throw new IllegalArgumentException("Время проверки RIA истекло");
    }

    private Object request(String path, Map<String, String> query, BooleanSupplier stop, long until) throws Exception {
        // All callers share the persisted 100-request budget, including direct watch refreshes.
        synchronized (REQUEST_LOCK) {
            running(stop, until);
            JSONObject quota = db.state(
                quotaKey,
                Json.object("started", System.currentTimeMillis(), "used", 0)
            );
            if (System.currentTimeMillis() - quota.optLong("started") >= 30L * 86400000L) quota =
                Json.object("started", System.currentTimeMillis(), "used", 0);
            if (quota.optInt("used") >= 100) throw new IllegalArgumentException(
                "Достигнут локальный лимит 100 запросов API за 30 дней"
            );
            if (
                quota.optLong("blockedUntil") > System.currentTimeMillis()
            ) throw new IllegalArgumentException("Повтор API отложен после отказа источника");
            long delay = quota.optLong("lastRequest") + 2200 - System.currentTimeMillis();
            if (delay > 0 && delay >= until - System.currentTimeMillis()) throw new IllegalArgumentException(
                "Время проверки RIA истекло"
            );
            if (delay > 0) Thread.sleep(delay);
            running(stop, until);
            Json.put(quota, "used", quota.optInt("used") + 1);
            Json.put(quota, "lastRequest", System.currentTimeMillis());
            db.saveState(quotaKey, quota);
            okhttp3.HttpUrl.Builder b = Network.safe("https://developers.ria.com" + path)
                .newBuilder()
                .addQueryParameter("api_key", key);
            for (Map.Entry<String, String> q : query.entrySet())
                b.addQueryParameter(q.getKey(), q.getValue());
            try {
                Object response = new JSONTokener(net.get(b.build().toString()).body).nextValue();
                running(stop, until);
                return response;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                if (
                    Objects.toString(e.getMessage(), "").contains("429") ||
                    Objects.toString(e.getMessage(), "").contains("403")
                ) {
                    Json.put(quota, "blockedUntil", System.currentTimeMillis() + 3600000L);
                    db.saveState(quotaKey, quota);
                }
                throw new IllegalArgumentException(
                    "Не удалось получить данные RIA. Проверь доступ и квоту в кабинете."
                );
            }
        }
    }

    static boolean nativeWatch(JSONObject row) {
        return row != null && ("domria".equals(row.optString("source")) || storedApiIdentity(row) != null);
    }

    private static boolean sameApiTarget(JSONObject marker, JSONObject row) {
        try {
            String id=marker.optString("externalId");
            return id.matches("[1-9][0-9]{0,11}") && id.equals(listingId(row.optString("url"))) &&
                marker.optString("listingId").equals(Model.identity(row)) &&
                !marker.optString("city").isBlank() &&
                Bilingual.city(marker.optString("city")).equals(Bilingual.city(row.optString("city"))) &&
                List.of("apartment", "house").contains(marker.optString("kind")) &&
                marker.optString("kind").equals(row.optString("kind")) &&
                "sale".equals(row.optString("deal", "sale"));
        } catch (Exception e) { return false; }
    }

    private static JSONObject storedApiIdentity(JSONObject row) {
        if (row == null) return null;
        JSONObject marker=row.optJSONObject(API_IDENTITY);
        return marker != null && sameApiTarget(marker, row) ? marker : null;
    }

    static void keepApiIdentity(JSONObject previous, JSONObject next, JSONObject input) {
        // Treat every incoming JSON marker as untrusted, including backups of native observations.
        next.remove(API_IDENTITY);
        JSONObject marker=null;
        if (input instanceof ApiObservation api && "native".equals(next.optString("priceEvidence")) &&
            api.externalId.equals(next.optString("externalId")) && api.city.equals(next.optString("city")) &&
            api.kind.equals(next.optString("kind"))) {
            JSONObject confirmed=Json.object("externalId", api.externalId, "city", api.city, "kind", api.kind,
                "listingId", Model.identity(next));
            if (sameApiTarget(confirmed, next)) marker=confirmed;
        }
        if (marker == null) marker=storedApiIdentity(previous);
        if (marker != null && sameApiTarget(marker, next)) Json.put(next, API_IDENTITY, Json.copy(marker));
    }

    private static String listingId(String value) {
        try {
            URI uri = URI.create(value);
            if (!"dom.ria.com".equals(Network.safe(value).host()) || uri.getRawFragment() != null) return "";
            Matcher match = LISTING_PATH.matcher(uri.getRawPath());
            return match.matches() ? match.group(1) : "";
        } catch (Exception e) { return ""; }
    }

    private static String watchId(JSONObject previous) {
        JSONObject marker=storedApiIdentity(previous);
        String id = marker == null ? previous.optString("externalId") : marker.optString("externalId");
        if (!nativeWatch(previous) || !id.matches("[1-9][0-9]{0,11}") ||
            !id.equals(listingId(previous.optString("url"))) || previous.optString("city").isBlank() ||
            !List.of("apartment", "house").contains(previous.optString("kind")) ||
            !"sale".equals(previous.optString("deal", "sale"))) throw new IllegalArgumentException(
                "RIA: исходное API-объявление и его ссылка не подтверждены"
            );
        return id;
    }

    JSONObject info(JSONObject previous, BooleanSupplier stop, long until) throws Exception {
        String id = watchId(previous);
        Object response = request("/dom/info/" + id, Map.of(), stop, until);
        if (!(response instanceof JSONObject payload)) throw new IllegalArgumentException(
            "RIA: неизвестный формат API-объявления"
        );
        return watched(payload, previous);
    }

    static JSONObject watched(JSONObject payload, JSONObject previous) {
        String id = watchId(previous);
        if (payload == null || payload.optString("city_name").isBlank()) throw new IllegalArgumentException(
            "RIA: город исходного объявления не подтверждён"
        );
        JSONObject fresh = normalize(payload, id, previous.optString("city"), previous.optString("kind"));
        if (fresh == null || !id.equals(listingId(fresh.optString("url")))) throw new IllegalArgumentException(
            "RIA: исходное объявление не подтверждено; прежняя цена не считается новой проверкой"
        );
        // The API has confirmed the original externalId, city and kind. A renamed beautiful_url
        // must update that listing's existing history rather than create a different Model key.
        Json.put(fresh, "url", previous.optString("url"));
        Json.put(fresh, "source", previous.optString("source"));
        Model.validate(fresh);
        return fresh;
    }

    private int location(String city) throws Exception {
        JSONObject saved = db.state("ria-city:" + city, new JSONObject());
        if (saved.optInt("cityId") > 0) return saved.optInt("cityId");
        String[] row = Arrays.stream(Sources.CITIES)
            .filter(c -> c[0].equals(city))
            .findFirst()
            .orElseThrow();
        JSONArray states = (JSONArray) request("/dom/states", Map.of("lang_id", "4"));
        int state = 0;
        for (JSONObject r : Json.objects(states))
            if (Model.normalized(r.optString("name")).contains(Model.normalized(row[1]))) {
                state = r.optInt("stateID");
                break;
            }
        if (state <= 0) throw new IllegalArgumentException("Область RIA не найдена");
        JSONArray cities = (JSONArray) request("/dom/cities/" + state, Map.of("lang_id", "4"));
        for (JSONObject r : Json.objects(cities))
            if (Model.normalized(r.optString("name")).equals(Model.normalized(city))) {
                db.saveState(
                    "ria-city:" + city,
                    Json.object("stateId", state, "cityId", r.optInt("cityID"))
                );
                return r.optInt("cityID");
            }
        throw new IllegalArgumentException("Город RIA не найден");
    }

    int next(JSONObject filters) throws Exception {
        String city = filters.optString("city");
        int cityId = location(city);
        JSONObject location = db.state("ria-city:" + city, new JSONObject());
        String kind = filters.optString("kind", "both"),
            stateKey = "ria-progress:" + city + ":" + kind;
        JSONObject progress = db.state(
            stateKey,
            Json.object("page", 0, "kind", "apartment", "pending", new JSONArray())
        );
        String current = kind.equals("both") ? progress.optString("kind", "apartment") : kind;
        List<String> pending = new ArrayList<>();
        JSONArray queue = progress.optJSONArray("pending");
        if (queue != null) for (int i = 0; i < queue.length(); i++) pending.add(queue.optString(i));
        if (pending.isEmpty()) {
            int page = progress.optInt("page");
            if (page > 99) throw new IllegalArgumentException(
                "Достигнут предел страниц официального API; поиск не объявляется полным"
            );
            Map<String, String> query = new LinkedHashMap<>();
            query.put("category", current.equals("house") ? "4" : "1");
            query.put("realty_type", current.equals("house") ? "5" : "2");
            query.put("operation_type", "1");
            query.put("state_id", String.valueOf(location.optInt("stateId")));
            query.put("city_id", String.valueOf(cityId));
            query.put("sort", "upd_d");
            query.put("page", String.valueOf(page));
            JSONObject found = (JSONObject) request("/dom/search", query);
            JSONArray items = found.optJSONArray("items");
            if (items == null) throw new IllegalArgumentException(
                "Неизвестный формат выдачи API RIA"
            );
            for (int i = 0; i < items.length(); i++) {
                String id = items.optString(i);
                if (id.matches("[1-9][0-9]{0,11}")) pending.add(id);
            }
            Json.put(progress, "pending", new JSONArray(pending));
            Json.put(progress, "currentKind", current);
            Json.put(progress, "count", found.optInt("count"));
            db.saveState(stateKey, progress);
        }
        int imported = 0;
        for (int i = 0; i < 2 && !pending.isEmpty(); i++) {
            String id = pending.get(0);
            JSONObject payload = (JSONObject) request("/dom/info/" + id, Map.of());
            JSONObject r = normalize(payload, id, city, current);
            if (r != null) imported += db.ingest(List.of(r), true);
            pending.remove(0);
            Json.put(progress, "pending", new JSONArray(pending));
            db.saveState(stateKey, progress);
        }
        if (pending.isEmpty()) {
            if (kind.equals("both") && current.equals("apartment")) Json.put(
                progress,
                "kind",
                "house"
            );
            else {
                Json.put(progress, "kind", "apartment");
                Json.put(progress, "page", progress.optInt("page") + 1);
            }
            db.saveState(stateKey, progress);
        }
        return imported;
    }

    static JSONObject normalize(JSONObject p, String id, String city, String expected) {
        if (
            p.optInt("realty_id") <= 0 ||
            !p.optString("realty_id").equals(id) ||
            p.optInt("operation_type") != 1
        ) return null;
        String kind = p.optInt("realty_type") == 2
            ? "apartment"
            : p.optInt("realty_type") == 5
                ? "house"
                : "";
        if (!kind.equals(expected)) return null;
        String currency = p.optString("currency_type");
        if (!List.of("USD", "UAH", "EUR").contains(currency)) {
            currency = switch (p.optInt("currency_type")) {
                case 1 -> "USD";
                case 2 -> "UAH";
                case 3 -> "EUR";
                default -> "";
            };
        }
        double price = p.optDouble("price_total", 0);
        if (price <= 0 || currency.isBlank()) return null;
        String actual = p.optString("city_name", city);
        if (!Bilingual.city(actual).equals(Bilingual.city(city))) return null;
        String slug = p.optString("beautiful_url");
        String url = slug.startsWith("https://dom.ria.com/")
            ? slug
            : slug.isBlank()
                ? "https://dom.ria.com/uk/realty-" + id + ".html"
                : "https://dom.ria.com/" + slug.replaceFirst("^/+", "");
        if (!Network.safe(url).host().equals("dom.ria.com")) return null;
        JSONObject r = Json.object(
            "source",
            "domria",
            "externalId",
            id,
            "url",
            url,
            "title",
            (kind.equals("house") ? "Будинок" : "Квартира") + " · " + actual,
            "titleEvidence",
            "generated",
            "city",
            actual,
            "kind",
            kind,
            "deal",
            "sale",
            "price",
            price,
            "currency",
            currency,
            "priceEvidence",
            "native",
            "observedAt",
            Model.now(),
            "status",
            "active",
            "sellerType",
            "unknown"
        );
        for (String[] field : new String[][] {
            { "total_square_meters", "area" },
            { "rooms_count", "rooms" },
            { "floor", "floor" },
            { "floors_count", "floorsTotal" },
            { "latitude", "latitude" },
            { "longitude", "longitude" },
        }) {
            double n = p.optDouble(field[0], Double.NaN);
            if (Double.isFinite(n)) Json.put(r, field[1], n);
        }
        String street = p.optString("street_name");
        if (!street.isBlank()) Json.put(
            r,
            "address",
            street +
                (p.optInt("is_show_building_no") == 1
                        ? ", " + p.optString("building_number_str")
                        : "")
        );
        Json.put(
            r,
            "district",
            p.optString("admin_district_name_uk", p.optString("district_name"))
        );
        return !p.optString("city_name").isBlank() && id.equals(listingId(url)) ? new ApiObservation(r) : r;
    }
}

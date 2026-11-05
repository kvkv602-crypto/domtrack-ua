package ua.domtrack.mobile;

import android.content.Context;
import java.util.*;
import org.json.*;

final class Ria {

    private final Database db;
    private final Network net = new Network();
    private final String key, quotaKey;

    Ria(Context c, Database d) throws Exception {
        db = d;
        key = new Secrets(c).get();
        if (key.isBlank()) throw new IllegalArgumentException("Сначала сохрани API-ключ RIA");
        quotaKey = "ria-quota:" + Model.hash(key);
    }

    private Object request(String path, Map<String, String> query) throws Exception {
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
        if (delay > 0) Thread.sleep(delay);
        Json.put(quota, "used", quota.optInt("used") + 1);
        Json.put(quota, "lastRequest", System.currentTimeMillis());
        db.state(quotaKey, quota);
        okhttp3.HttpUrl.Builder b = Network.safe("https://developers.ria.com" + path)
            .newBuilder()
            .addQueryParameter("api_key", key);
        for (Map.Entry<String, String> q : query.entrySet())
            b.addQueryParameter(q.getKey(), q.getValue());
        try {
            return new JSONTokener(net.get(b.build().toString()).body).nextValue();
        } catch (Exception e) {
            if (
                Objects.toString(e.getMessage(), "").contains("429") ||
                Objects.toString(e.getMessage(), "").contains("403")
            ) {
                Json.put(quota, "blockedUntil", System.currentTimeMillis() + 3600000L);
                db.state(quotaKey, quota);
            }
            throw new IllegalArgumentException(
                "Не удалось получить данные RIA. Проверь доступ и квоту в кабинете."
            );
        }
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
                db.state(
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
            db.state(stateKey, progress);
        }
        int imported = 0;
        for (int i = 0; i < 2 && !pending.isEmpty(); i++) {
            String id = pending.get(0);
            JSONObject payload = (JSONObject) request("/dom/info/" + id, Map.of());
            JSONObject r = normalize(payload, id, city, current);
            if (r != null) imported += db.ingest(List.of(r), true);
            pending.remove(0);
            Json.put(progress, "pending", new JSONArray(pending));
            db.state(stateKey, progress);
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
            db.state(stateKey, progress);
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
        if (!Model.normalized(actual).equals(Model.normalized(city))) return null;
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
        return r;
    }
}

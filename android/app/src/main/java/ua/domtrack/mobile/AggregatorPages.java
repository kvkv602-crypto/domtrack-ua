package ua.domtrack.mobile;

import java.util.*;
import java.util.regex.*;
import org.json.*;
import org.jsoup.nodes.*;

/** Reads public HTML bootstrap data without executing scripts or calling private APIs. */
final class AggregatorPages {

    static Sources.Result parse(Document doc, String pageUrl, Sources.Section section, int page) {
        boolean flatfy = section.source.equals("flatfy");
        if (flatfy) verifyFlatfy(doc, section, page);
        else Pagination.verify(doc, pageUrl, section, page);
        Map<String, Element> visible = new LinkedHashMap<>();
        for (Element card : doc.select(
            flatfy ? ".realty-preview[id]" : "[class*=RealtyCard-][class*=__root]"
        )) {
            if (!Sources.visible(card)) continue;
            String id = flatfy ? card.id() : "";
            if (!flatfy) for (Element element : card.select("[data-event-options]")) {
                Matcher m = Pattern.compile("(?:^|\\|)page_id:([0-9]+)(?:\\||$)").matcher(
                    element.attr("data-event-options")
                );
                if (m.find()) {
                    id = m.group(1);
                    break;
                }
            }
            if (id.matches("[1-9][0-9]{0,17}")) visible.putIfAbsent(id, card);
        }
        Map<String, JSONObject> payload = new HashMap<>();
        StringBuilder flight = new StringBuilder();
        for (Element script : doc.select("script:not([src])")) {
            String raw = script.data().trim();
            try {
                if (flatfy && raw.contains("window.INITIAL_STATE")) {
                    // The surrounding JavaScript object contains undefined. Parse only the JSON array.
                    Matcher m = Pattern.compile(
                        "\\\"realties\\\"\\s*:\\s*\\{\\s*\\\"list\\\"\\s*:\\s*"
                    ).matcher(raw);
                    if (m.find()) collect(
                        new JSONTokener(raw.substring(m.end())).nextValue(),
                        payload,
                        0
                    );
                } else if (!flatfy && raw.startsWith("self.__next_f.push(")) {
                    Object data = new JSONTokener(
                        raw.substring("self.__next_f.push(".length())
                    ).nextValue();
                    if (!(data instanceof JSONArray)) continue;
                    Object chunk = ((JSONArray) data).opt(1);
                    if (!(chunk instanceof String)) continue;
                    flight.append((String) chunk);
                }
            } catch (Exception ignored) {}
        }
        if (!flatfy) {
            // Flight text records are length-delimited and need not end with a newline.
            // The public cards array is JSON even when its surrounding stream is not.
            Matcher m = Pattern.compile(
                "\\\"realties\\\"\\s*:\\s*\\{\\s*\\\"cards\\\"\\s*:\\s*"
            ).matcher(flight);
            while (m.find()) try {
                collect(new JSONTokener(flight.substring(m.end())).nextValue(), payload, 0);
            } catch (Exception ignored) {}
        }
        List<JSONObject> schema = new ArrayList<>();
        for (Element script : doc.select("script[type=application/ld+json]"))
            try {
                collectSchema(new JSONTokener(script.data()).nextValue(), schema, 0);
            } catch (Exception ignored) {}
        LinkedHashMap<String, JSONObject> records = new LinkedHashMap<>();
        Set<String> conflicts = new HashSet<>();
        for (String id : visible.keySet()) {
            JSONObject card = payload.get(id);
            if (card == null) continue;
            JSONObject record = record(card, schema, section, flatfy);
            if (record == null) continue;
            String url = record.optString("url");
            JSONObject old = records.get(url);
            if (
                old != null &&
                (old.optDouble("price") != record.optDouble("price") ||
                    !old.optString("currency").equals(record.optString("currency")))
            ) {
                records.remove(url);
                conflicts.add(url);
            } else if (!conflicts.contains(url)) records.put(url, record);
        }
        if (visible.isEmpty() || records.isEmpty()) throw new IllegalArgumentException(
            "Публичные карточки " +
                section.source +
                " не подтверждены: доступ или разметка изменились"
        );
        boolean end = false;
        if (flatfy) {
            int maximum = page;
            for (Element a : doc.select("a.paging-button[href]")) {
                Integer n = Pagination.page(a.attr("href"), section.base);
                if (n != null) maximum = Math.max(maximum, n);
            }
            end = maximum == page && doc.select("a.paging-nav--right[href]").isEmpty();
        }
        // LUN pagination is currently disallowed by robots.txt. Page 1 must never imply full coverage.
        return new Sources.Result(
            new ArrayList<>(records.values()),
            end,
            visible.size(),
            new ArrayList<>(visible.keySet())
        );
    }

    private static void verifyFlatfy(Document doc, Sources.Section section, int requested) {
        List<Element> active = doc.select("a.paging-button.active-link");
        if (
            active.size() != 1 ||
            !Sources.text(active.get(0)).equals(String.valueOf(requested)) ||
            !Objects.equals(Pagination.page(active.get(0).attr("href"), section.base), requested)
        ) throw new IllegalArgumentException("Flatfy вернул другую страницу или раздел");
        // Flatfy declares LUN as its canonical domain; the visible pager verifies the actual Flatfy route.
    }

    private static void collect(Object value, Map<String, JSONObject> cards, int depth) {
        if (depth > 24) return;
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            if (o.has("id") && (o.has("urlRaw") || o.has("url_raw"))) {
                cards.putIfAbsent(o.optString("id"), o);
                return;
            }
            for (Iterator<String> it = o.keys(); it.hasNext(); ) collect(
                o.opt(it.next()),
                cards,
                depth + 1
            );
        } else if (value instanceof JSONArray) for (
            int i = 0;
            i < ((JSONArray) value).length();
            i++
        ) collect(((JSONArray) value).opt(i), cards, depth + 1);
    }

    private static void collectSchema(Object value, List<JSONObject> items, int depth) {
        if (depth > 16) return;
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            if (
                o.optJSONObject("offers") != null &&
                o.optJSONObject("geo") != null &&
                o.has("description")
            ) {
                items.add(o);
                return;
            }
            for (Iterator<String> it = o.keys(); it.hasNext(); ) collectSchema(
                o.opt(it.next()),
                items,
                depth + 1
            );
        } else if (value instanceof JSONArray) for (
            int i = 0;
            i < ((JSONArray) value).length();
            i++
        ) collectSchema(((JSONArray) value).opt(i), items, depth + 1);
    }

    private static String key(boolean flatfy, String snake, String camel) {
        return flatfy ? snake : camel;
    }

    private static JSONObject record(
        JSONObject card,
        List<JSONObject> schema,
        Sources.Section s,
        boolean flatfy
    ) {
        if (
            card.optInt(key(flatfy, "section_id", "sectionId")) != (s.kind.equals("house") ? 3 : 1)
        ) return null;
        if (
            !card
                .optString(key(flatfy, "group_page_status", "groupPageStatus"), "active")
                .equals("active")
        ) return null;
        String city = "",
            district = "";
        for (JSONObject geo : Json.objects(
            card.optJSONArray(key(flatfy, "geo_entities", "geoEntities"))
        )) {
            if (geo.optString("type").equals("city")) city = geo.optString("name");
            if (geo.optString("type").equals("district")) district = geo.optString("name");
        }
        if (!Bilingual.city(city).equals(Bilingual.city(s.city))) return null;
        String url = card.optString(key(flatfy, "url_raw", "urlRaw")),
            title = card.optString("header");
        try {
            Network.safe(url);
            Model.identity(Json.object("url", url));
        } catch (Exception e) {
            return null;
        }
        if (title.isBlank()) return null;
        String description = card.optString("text"),
            currency = card.optString("currency").toUpperCase(Locale.ROOT);
        JSONObject r = Json.object(
            "url",
            url,
            "externalId",
            card.optString("id"),
            "source",
            s.source,
            "discoveredVia",
            Json.array(List.of(s.source)),
            "title",
            title,
            "address",
            title,
            "district",
            district,
            "city",
            s.city,
            "region",
            s.region,
            "kind",
            s.kind,
            "deal",
            "sale",
            "price",
            card.optDouble("price"),
            "currency",
            currency,
            "sellerType",
            card.optBoolean(key(flatfy, "is_owner", "isOwner")) ? "owner" : "unknown",
            "status",
            "active",
            "observedAt",
            Model.now()
        );
        for (String[] field : List.of(
            new String[] { "rooms", "room_count", "roomCount" },
            new String[] { "area", "area_total", "areaTotal" },
            new String[] { "floor", "floor", "floor" },
            new String[] { "floorsTotal", "floor_count", "floorCount" }
        )) {
            String name = key(flatfy, field[1], field[2]);
            if (
                card.has(name) && !card.isNull(name) && Double.isFinite(card.optDouble(name))
            ) Json.put(r, field[0], card.optDouble(name));
        }
        JSONArray location = card.optJSONArray("location");
        if (location != null && location.length() == 2) {
            double lon = location.optDouble(0),
                lat = location.optDouble(1);
            if (
                Double.isFinite(lat) &&
                Double.isFinite(lon) &&
                Math.abs(lat) <= 90 &&
                Math.abs(lon) <= 180
            ) {
                Json.put(r, "latitude", lat);
                Json.put(r, "longitude", lon);
                Json.put(r, "locationAccuracy", "source");
            }
        }
        if (description.matches("\\$[0-9a-f]+")) {
            description = "";
            for (JSONObject item : schema) {
                JSONObject offer = item.optJSONObject("offers");
                if (
                    Bilingual.text(item.optString("name")).equals(Bilingual.text(title)) &&
                    offer.optDouble("price") == r.optDouble("price") &&
                    offer.optString("priceCurrency").equalsIgnoreCase(currency) &&
                    item.optInt("numberOfRooms") == r.optInt("rooms")
                ) {
                    description = item.optString("description");
                    break;
                }
            }
        }
        if (
            description.matches(
                "(?isu).*(?:част[ьиі] (?:дома|будинку)|половина (?:дома|будинку)|продаж (?:частини|кімнати)|продажа комнаты).*"
            )
        ) return null;
        Json.put(
            r,
            "description",
            description.length() > 18000 ? description.substring(0, 18000) : description
        );
        for (JSONObject image : Json.objects(card.optJSONArray("images"))) {
            String src = image.optString("url");
            if (src.startsWith("https://")) {
                Json.put(r, "image", src);
                break;
            }
        }
        try {
            Model.validate(r);
            return r;
        } catch (Exception e) {
            return null;
        }
    }
}

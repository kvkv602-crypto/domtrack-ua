package ua.domtrack.mobile;

import java.util.*;
import java.net.URI;
import java.util.regex.*;
import org.json.*;
import org.jsoup.nodes.*;

/** Public REM sale pages, bound to their own property URLs and supported city routes. */
final class RemPage {
    // Literal city slugs from REM's public homepage sale links, checked 2026-11-10.
    private static final Map<String, String> CITIES = Map.ofEntries(
        Map.entry("Київ", "kiev"), Map.entry("Львів", "lvov"), Map.entry("Одеса", "odessa"),
        Map.entry("Дніпро", "dnepr"), Map.entry("Миколаїв", "nikolaev"), Map.entry("Чернігів", "chernigov"),
        Map.entry("Ужгород", "uzhgorod"), Map.entry("Івано-Франківськ", "ivano_frankovsk"),
        Map.entry("Тернопіль", "ternopol"), Map.entry("Чернівці", "chernovci"), Map.entry("Рівне", "rovno"),
        Map.entry("Хмельницький", "hmelnickiy"), Map.entry("Вінниця", "vinnitsya"),
        Map.entry("Житомир", "zhutomir"), Map.entry("Кропивницький", "kropivnickiy"),
        Map.entry("Черкаси", "cherkassy"), Map.entry("Суми", "sumy"), Map.entry("Полтава", "poltava"),
        Map.entry("Запоріжжя", "zaporozhe"), Map.entry("Херсон", "herson"), Map.entry("Харків", "kharkov")
    );
    private static final Pattern PROPERTY = Pattern.compile(
        "^/prodazha-(kvartira|doma)-(?:[1-9][0-9]{0,2}-komn-)?[0-9]{1,8}-kv-m-([a-z0-9_]+)(?:-[a-z0-9_-]+)?-db([1-9][0-9]{0,11})$");

    private static IllegalArgumentException failure(String message) { return new IllegalArgumentException("REM.UA: " + message); }

    static Sources.Section section(String city, String region, String kind) {
        if (!List.of("apartment", "house").contains(kind)) return null;
        for (Map.Entry<String, String> entry : CITIES.entrySet())
            if (Bilingual.city(entry.getKey()).equals(Bilingual.city(city)))
                return new Sources.Section("rem", city, region, kind, "https://rem.ua/prodazha-" +
                    (kind.equals("house") ? "domov-" : "kvartir-") + entry.getValue());
        return null;
    }

    private static URI safe(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || !"rem.ua".equals(uri.getHost()) || uri.getUserInfo() != null ||
                    (uri.getPort() != -1 && uri.getPort() != 443) || uri.getRawFragment() != null ||
                    value.length() > 2048 || value.matches("(?s).*[\\x00-\\x20\\\\].*")) return null;
            return uri;
        } catch (IllegalArgumentException ignored) { return null; }
    }

    private static String path(URI uri) { return uri.getRawPath().replaceFirst("^/ua/", "/"); }

    private static final class Property {
        final String url, id, city, region, kind;
        Property(String u, String i, String c, String r, String k) { url=u;id=i;city=c;region=r;kind=k; }
    }

    private static Property property(String value) {
        URI uri = safe(value);
        if (uri == null || uri.getRawQuery() != null) return null;
        String path = path(uri);
        Matcher match = PROPERTY.matcher(path);
        if (!match.matches()) return null;
        for (Map.Entry<String, String> entry : CITIES.entrySet()) if (match.group(2).equals(entry.getValue())) {
            String[] city = Arrays.stream(Sources.CITIES).filter(row -> row[0].equals(entry.getKey())).findFirst().orElseThrow();
            return new Property("https://rem.ua" + path, match.group(3), city[0], city[1],
                match.group(1).equals("doma") ? "house" : "apartment");
        }
        return null;
    }

    private static int catalogPage(String value, Sources.Section section) {
        URI uri = safe(value), base = safe(section.base);
        if (uri == null || base == null || !path(uri).equals(path(base))) return -1;
        String query = uri.getRawQuery();
        if (query == null) return 1;
        return query.matches("page=[1-9][0-9]{0,4}") ? Integer.parseInt(query.substring(5)) : -1;
    }

    private static Element single(Element scope, String selector) {
        List<Element> nodes = scope.select(selector).stream().filter(Sources::visible).toList();
        if (nodes.size() != 1) throw failure("структура объявления не подтверждена");
        return nodes.get(0);
    }

    static Sources.Result parse(Document doc, String url, Sources.Section section, int page) {
        Sources.Section known = section(section.city, section.region, section.kind);
        if (known == null || !known.base.equals(section.base) || catalogPage(url, section) != page)
            throw failure("раздел или страница не подтверждены");
        List<Element> canonicals = doc.select("head link[rel=canonical]");
        if (canonicals.size() != 1 || catalogPage(canonicals.get(0).absUrl("href"), section) != page)
            throw failure("сайт вернул другой раздел или страницу");
        List<String> active = doc.select(".pagination li.active").stream().map(Sources::text).distinct().toList();
        boolean noPager = active.isEmpty();
        if (!noPager && !active.equals(List.of(String.valueOf(page)))) throw failure("номер страницы не подтверждён");
        List<Element> next = doc.select(".pagination a[rel=next]");
        if (next.size() > 1 || (!next.isEmpty() && catalogPage(next.get(0).absUrl("href"), section) != page + 1))
            throw failure("следующая страница не подтверждена");
        if (noPager && (page != 1 || !next.isEmpty())) throw failure("номер страницы не подтверждён");
        LinkedHashMap<String, JSONObject> rows = new LinkedHashMap<>();
        Set<String> conflicts = new HashSet<>();
        List<String> observed = new ArrayList<>();
        int count = 0;
        for (Element card : doc.select(".object-item")) {
            if (!Sources.visible(card)) continue;
            count++;
            try {
                Property property = property(single(card, "a.object_block-link[href]").absUrl("href"));
                if (property == null) continue;
                observed.add(property.url);
                if (!property.kind.equals(section.kind) || !Bilingual.city(property.city).equals(Bilingual.city(section.city))) continue;
                for (Element link : card.select("a[href]")) {
                    Property other = property(link.absUrl("href"));
                    if (other != null && !property.url.equals(other.url)) throw failure("в карточке несколько объектов");
                }
                String title = Sources.text(single(card, ".object-address"));
                String description = Sources.text(single(card, ".object-card-description"));
                if (!wholeSale(title, description)) continue;
                JSONObject price = Sources.price(Sources.text(single(card, ".object-price")), true);
                if (price == null) continue;
                JSONObject row = record(property, title, price);
                Json.put(row, "description", description.substring(0, Math.min(18000, description.length())));
                catalogFacts(card, row);
                List<Element> coords = card.select(".object_coords");
                if (coords.size() == 1) geo(row, Sources.text(coords.get(0).selectFirst(".object_lg")), Sources.text(coords.get(0).selectFirst(".object_lt")));
                Element image = card.selectFirst(".object-item__img");
                if (image != null) ListingPhotos.card(image, row);
                String updated = Sources.text(card.selectFirst(".object-card-updated .value"));
                if (!updated.isEmpty()) Json.put(row, "sourceUpdatedAtText", updated);
                Model.validate(row);
                JSONObject previous = rows.get(property.url);
                if (previous != null && (previous.optDouble("price") != row.optDouble("price") ||
                        !previous.optString("currency").equals(row.optString("currency")))) {
                    conflicts.add(property.url); rows.remove(property.url);
                } else if (!conflicts.contains(property.url)) rows.put(property.url, row);
            } catch (IllegalArgumentException ignored) { /* Unsupported cards cannot prove an observation. */ }
        }
        if (count == 0 || rows.isEmpty()) throw failure("публичные карточки продажи не подтверждены; доступ или разметка изменились");
        int total = 0;
        Element counter = doc.selectFirst(".container-objects-counter .objects-counter");
        if (counter != null) {
            Matcher number = Pattern.compile("^([0-9][0-9 ]*)\\s*(?:объект|об.?єкт).*$", Pattern.UNICODE_CASE).matcher(Sources.text(counter));
            if (number.matches()) try { total = Integer.parseInt(number.group(1).replace(" ", "")); } catch (NumberFormatException ignored) {}
        }
        boolean end = next.isEmpty() && total > 0 && page == (int) Math.ceil(total / 25.0)
            && count == total - (page - 1) * 25;
        if (noPager && !end) throw failure("единственная страница не подтверждена общим числом объектов");
        return new Sources.Result(new ArrayList<>(rows.values()), end, count, observed);
    }

    private static JSONObject record(Property property, String title, JSONObject price) {
        return Json.object("url", property.url, "externalId", property.id, "source", "rem", "title", title,
            "city", property.city, "region", property.region, "kind", property.kind, "deal", "sale", "period", "total",
            "price", price.optDouble("price"), "currency", price.optString("currency"), "priceEvidence", "display",
            "sellerType", "unknown", "status", "active", "observedAt", Model.now());
    }

    private static boolean wholeSale(String title, String description) {
        if (title.isBlank() || title.length() > 2000 || title.matches("(?isu).*(?:аренд|оренд|здам|сдам|обмін|обмен|rent).*")) return false;
        String text = title + " " + description;
        return !Pattern.compile("(?iu)(?:пол\\s*дома|пів\\s*будин|половин\\p{L}*\\s+(?:дом|будин)|" +
            "(?:част(?:ь|ка|ку|ина|ину)|дол[яюи])\\s+(?:\\S+\\s+){0,2}(?:квартир|дом|будин)|" +
            "\\d+\\s*/\\s*\\d+\\s*(?:част|квартир|дом|будин)|" +
            "(?:продам|продаж|продажа|продаю)\\s+(?:жилую\\s+)?(?:комнату|кімнату)(?=$|[^\\p{L}]))")
            .matcher(text).find();
    }

    private static void fact(JSONObject row, String name, double value) {
        if (!Double.isFinite(value) || value < (name.equals("floor") ? -10 : 0) || value > 10000000 ||
                (!name.equals("floor") && value == 0) || (row.has(name) && row.optDouble(name) != value))
            throw failure("характеристики противоречивы");
        Json.put(row, name, value);
        if (row.has("floor") && row.has("floorsTotal") && row.optDouble("floor") > row.optDouble("floorsTotal"))
            throw failure("этаж выше этажности дома");
    }

    private static double number(String value) { return Double.parseDouble(value.replace(',', '.')); }

    private static void catalogFacts(Element card, JSONObject row) {
        String summary = Sources.text(single(card, ".object-city-region"));
        Matcher rooms = Pattern.compile("(?iu)^(\\d{1,3})\\s*-\\s*комн\\.").matcher(summary);
        if (row.optString("kind").equals("apartment")) {
            if (!rooms.find()) throw failure("тип квартиры не подтверждён");
            fact(row, "rooms", number(rooms.group(1)));
        } else if (!summary.matches("(?iu)^(?:Дом|Будинок)\\s*,.*")) throw failure("тип дома не подтверждён");
        Matcher area = Pattern.compile("(?iu)(\\d+(?:[.,]\\d+)?)\\s*кв\\.\\s*м\\.").matcher(summary);
        if (area.find()) fact(row, "area", number(area.group(1)));
        for (Element param : card.select(".object-param")) {
            String label = Model.normalized(Sources.text(param.selectFirst(".object-param__label")));
            String value = Sources.text(param.selectFirst(".object-param__value"));
            if (label.matches("поверховий|этажный")) {
                Matcher count = Pattern.compile("^(\\d{1,3})\\s*-?$").matcher(value);
                if (count.matches()) fact(row, "floorsTotal", number(count.group(1)));
            } else if (label.matches("поверх|этаж")) {
                Matcher floors = Pattern.compile("^(\\d{1,3})\\s*(?:з|із|из|/)\\s*(\\d{1,3})$").matcher(value);
                if (floors.matches()) { fact(row, "floor", number(floors.group(1))); fact(row, "floorsTotal", number(floors.group(2))); }
            }
        }
        String[] parts = summary.split(",");
        if (parts.length > 2 && !parts[2].isBlank()) Json.put(row, "district", parts[2].trim());
    }

    private static void geo(JSONObject row, String longitude, String latitude) {
        try {
            if (!longitude.matches("-?\\d+(?:\\.\\d+)?") || !latitude.matches("-?\\d+(?:\\.\\d+)?")) return;
            double lon = Double.parseDouble(longitude), lat = Double.parseDouble(latitude);
            if (lat == 0 || lon == 0 || lat < -90 || lat > 90 || lon < -180 || lon > 180) return;
            Json.put(row, "latitude", lat); Json.put(row, "longitude", lon); Json.put(row, "locationAccuracy", "source-unspecified");
        } catch (NumberFormatException ignored) {}
    }

    static JSONObject detail(Document doc, String url) {
        Property requested = property(url);
        List<Element> canonicals = doc.select("head link[rel=canonical]");
        Property canonical = canonicals.size() == 1 ? property(canonicals.get(0).absUrl("href")) : null;
        if (requested == null || canonical == null || !requested.url.equals(canonical.url))
            throw failure("исходная ссылка объявления не подтверждена");
        String title = Sources.text(single(doc, ".description-container__title"));
        String description = Sources.text(single(doc, ".description-container__desc"));
        if (!wholeSale(title, description) || !title.matches(requested.kind.equals("house")
                ? "(?iu)^(?:Продаж|Продажа|Продам)\\s+(?:будин|дом).*"
                : "(?iu)^(?:Продаж|Продажа|Продам)\\s+квартир.*"))
            throw failure("продажа целого объекта не подтверждена");
        JSONObject price = Sources.price(Sources.text(single(doc, ".object-info__price")), true);
        if (price == null) throw failure("полная цена объявления не подтверждена");
        JSONObject row = record(requested, title, price);
        Json.put(row, "titleEvidence", "generated"); Json.put(row, "structured", true); Json.put(row, "observationKind", "detail");
        Json.put(row, "description", description.substring(0, Math.min(18000, description.length())));
        String address = Sources.text(doc.selectFirst(".object-info__street"));
        if (!address.isBlank()) Json.put(row, "address", address);
        Element specs = single(doc, ".all-object-characteristic");
        for (Element spec : specs.select("li")) {
            String name = Model.normalized(Sources.text(spec.selectFirst(".name"))).replaceFirst(":$", "").trim();
            String value = Sources.text(spec.selectFirst(".value"));
            String field = switch (name) {
                case "кімнат", "комнат" -> "rooms";
                case "загальна площа", "общая площадь" -> "area";
                case "площа кухні", "площадь кухни" -> "kitchenArea";
                case "житлова площа", "жилая площадь" -> "livingArea";
                case "поверх", "этаж" -> "floor";
                case "поверховість", "этажность" -> "floorsTotal";
                default -> "";
            };
            if (!field.isEmpty()) {
                Matcher numeric = Pattern.compile("^(-?\\d+(?:[.,]\\d+)?)\\s*(?:м[²2])?$").matcher(value);
                if (numeric.matches()) fact(row, field, number(numeric.group(1)));
            } else if (name.equals("район") && !value.isBlank()) Json.put(row, "district", value);
        }
        List<Element> sliders = doc.select("#object-slider object-slider");
        if (sliders.size() == 1) try { ListingPhotos.metadata(row, new JSONArray(sliders.get(0).attr(":images")), url); }
            catch (JSONException ignored) {}
        String longitude = assignment(doc, "lat"), latitude = assignment(doc, "lng");
        // REM's public template assigns longitude to .lat and latitude to .lng; the catalog confirms this order.
        geo(row, longitude, latitude);
        publicContacts(doc, row);
        Model.validate(row);
        return row;
    }

    private static String assignment(Document doc, String name) {
        Pattern pattern = Pattern.compile("defaultCoordinates\\." + name + "\\s*=\\s*['\"]\\s*(-?\\d+(?:\\.\\d+)?)\\s*['\"]\\s*;");
        List<String> values = new ArrayList<>();
        for (Element script : doc.select("script:not([src])")) {
            Matcher match = pattern.matcher(script.data());
            while (match.find()) values.add(match.group(1));
        }
        return values.size() == 1 ? values.get(0) : "";
    }

    private static void publicContacts(Document doc, JSONObject row) {
        Set<String> phones = new HashSet<>(), emails = new HashSet<>();
        for (Element link : doc.select(".object-wrapper-btns a[href],.agent-data a[href]")) {
            if (!Sources.visible(link)) continue;
            String href = link.attr("href");
            if (href.startsWith("tel:")) {
                String phone = href.substring(4).replaceAll("[ ()-]", "");
                if (phone.matches("\\+?[0-9]{7,15}")) phones.add(phone);
            } else if (href.startsWith("mailto:")) {
                String email = href.substring(7);
                if (email.matches("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")) emails.add(email);
            }
        }
        if (phones.size() == 1) Json.put(row, "contactPhone", phones.iterator().next());
        if (emails.size() == 1) Json.put(row, "contactEmail", emails.iterator().next());
    }
}

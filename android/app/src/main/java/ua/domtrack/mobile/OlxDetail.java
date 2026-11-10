package ua.domtrack.mobile;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;
import org.json.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.select.Elements;

/** Reads a canonically bound public detail and photo URLs without fetching images or scripts. */
final class OlxDetail {
    private static IllegalArgumentException failure(String reason) {
        return new IllegalArgumentException("OLX: " + reason);
    }

    static JSONObject parse(String html, String requestedUrl, String responseUrl, JSONObject expected) {
        ListingLinks.Olx requested = ListingLinks.olx(requestedUrl);
        if (requested == null || !ListingLinks.sameOlx(requestedUrl, responseUrl))
            throw failure("исходная ссылка и идентификатор объявления не подтверждены");
        if (html == null || html.getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024)
            throw failure("страница объявления превышает допустимый размер");
        Document doc = Jsoup.parse(html, responseUrl);
        if (doc.getAllElements().size() > 100000) throw failure("слишком сложная страница");
        // Canonical links are document metadata: hiding one cannot erase an identity conflict.
        List<Element> canonicals = doc.select("link[rel]").stream()
            .filter(e -> Arrays.asList(e.attr("rel").toLowerCase(Locale.ROOT).trim().split("\\s+")).contains("canonical")).toList();
        Element canonical = canonicals.size() == 1 ? canonicals.get(0) : null;
        String canonicalUrl = canonical == null ? "" : canonical.attr("href");
        ListingLinks.Olx original = ListingLinks.olx(canonicalUrl);
        if (canonical == null || canonical.parent() != doc.head() || original == null ||
            URI.create(canonicalUrl).getRawQuery() != null || !ListingLinks.sameOlx(requestedUrl, canonicalUrl))
            throw failure("единственная исходная ссылка объявления не подтверждена");

        for (Element e : doc.select("script,style,template,noscript,iframe")) e.remove();
        for (Element e : new ArrayList<>(doc.getAllElements()))
            if (!Sources.visible(e) || e.attr("aria-hidden").trim().equalsIgnoreCase("true")) e.remove();
        if (contains(Sources.text(doc.body()), "access\\s+denied|request\\s+(?:blocked|rejected)|captcha|доступ\\s+(?:заборонено|обмежено|запрещ[её]н|ограничен)"))
            throw failure("площадка ограничила доступ или требует проверку; загрузка остановлена");

        Element breadcrumbs = single(doc, "[data-testid=breadcrumbs]"),
            titleContainer = single(doc, "[data-testid=offer_title]"),
            priceContainer = single(doc, "[data-testid=ad-price-container]"),
            props = single(doc, "[data-testid=ad-parameters-container]");
        Sources.Section location = location(breadcrumbs, responseUrl);
        if (expected != null &&
            (!Sources.cityIdentity(expected.optString("city")).equals(Sources.cityIdentity(location.city)) ||
                !expected.optString("kind").equals(location.kind) ||
                (expected.optString("observationKind").equals("detail") &&
                    !expected.optString("externalId", requested.id).equals(requested.id))))
            throw failure("город, тип жилья или идентификатор не совпадает с наблюдаемым объявлением");

        String title = Sources.text(single(titleContainer, "h4"));
        Element priceNode = single(priceContainer, "h3");
        JSONObject price = Sources.price(Sources.text(priceNode), false);
        Element outside = priceContainer.clone();
        outside.select("h3").remove();
        if (title.isBlank() || title.length() > 2000 || price == null ||
            contains(title, "^(?:кімнат[ауиі]|комнат[ауы])(?=$|[^\\p{L}])") ||
            contains(Sources.text(outside), "\\$|€|\\b(?:USD|UAH|EUR)\\b|грн"))
            throw failure("однозначный заголовок и полная цена объявления не подтверждены");
        Elements descriptions = doc.select("[data-testid=ad_description]");
        if (descriptions.size() > 1) throw failure("описание объявления неоднозначно");
        String description = descriptions.isEmpty() ? "" : Sources.text(descriptions.get(0));
        String priceText = Sources.text(priceContainer);
        if (contains(priceText, "(?:за|в|на|/|per)\\s*(?:місяц\\p{L}*|месяц\\p{L}*|міс\\.?|мес\\.?|доб[ауиі]|день|дн\\.?|month|day)(?=$|[^\\p{L}])") ||
            notWholeSale(title + " " + description + " " + Sources.text(props) + " " + priceText) ||
            contains(Sources.text(doc.body()), "(?:оголошення|объявление)\\s+(?:(?:більше|вже|больше|уже)\\s+)?не\\s*активн"))
            throw failure("продажа целого активного объекта не подтверждена; аренда, доли и цена за площадь не импортируются");

        JSONObject record = Json.object(
            "url", original.url, "externalId", original.id, "source", "olx", "title", title,
            "city", location.city, "region", location.region, "kind", location.kind,
            "deal", "sale", "period", "total", "price", price.optDouble("price"),
            "currency", price.optString("currency"), "priceEvidence", "display",
            "observationKind", "detail", "structured", false, "sellerType", "unknown",
            "status", "active", "observedAt", Model.now()
        );
        parameters(props, record);
        if (!description.isBlank()) Json.put(record, "description", description.substring(0, Math.min(18000, description.length())));
        ListingPhotos.olxGallery(doc, record);
        Model.validate(record);
        return record;
    }

    private static Element single(Element parent, String selector) {
        Elements nodes = parent.select(selector);
        if (nodes.size() != 1) throw failure("видимая структура объявления не подтверждена");
        return nodes.get(0);
    }

    private static Sources.Section location(Element breadcrumbs, String pageUrl) {
        if (contains(Sources.text(breadcrumbs), "оренд|аренд")) throw failure("раздел продажи не подтверждён");
        Map<String, Sources.Section> locations = new HashMap<>();
        Sources.Section confirmed = null;
        // Only the city routes already verified and registered in this app may establish locality.
        List<Sources.Section> routes = new ArrayList<>();
        for (String[] city : Sources.CITIES)
            for (Sources.Section route : Sources.sections(city[0], "both"))
                if (route.source.equals("olx")) routes.add(route);
        for (Element link : breadcrumbs.select("a[href]")) {
            URI u;
            try {
                u = URI.create(pageUrl).resolve(link.attr("href"));
                if (!"https".equals(u.getScheme()) || !ListingLinks.olxHost(u.toString()) ||
                    u.getUserInfo() != null || u.getPort() != -1 || u.getRawPath().contains("%")) continue;
            } catch (Exception e) { continue; }
            Sources.Section found = null;
            for (Sources.Section route : routes)
                if (URI.create(route.base).getRawPath().equals(u.getRawPath())) found = route;
            if (found == null) continue;
            locations.put(found.key(), found);
            Matcher label = Pattern.compile("(?iu)^(Продаж квартир|Продажа квартир|Продаж будинків|Продажа домов)\\s*[-–—]\\s*(.+)$")
                .matcher(Sources.text(link));
            if (!label.matches()) continue;
            String city = Sources.cityIdentity(label.group(2));
            boolean knownCity = Arrays.stream(Sources.CITIES).anyMatch(row -> Sources.cityIdentity(row[0]).equals(city));
            if (!knownCity) continue; // District breadcrumbs use the same city URL, but name a district.
            String kind = label.group(1).toLowerCase(Locale.ROOT).contains("квартир") ? "apartment" : "house";
            if (!city.equals(Sources.cityIdentity(found.city)) || !kind.equals(found.kind))
                throw failure("город или тип жилья в навигации противоречит ссылке раздела");
            confirmed = found;
        }
        if (locations.size() != 1 || confirmed == null)
            throw failure("город и раздел продажи не подтверждены ссылками навигации");
        return confirmed;
    }

    private static boolean contains(String value, String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(value).find();
    }

    private static boolean notWholeSale(String value) {
        String text = Normalizer.normalize(value, Normalizer.Form.NFKC).replace('\u2044', '/');
        if (contains(text, "(?:^|[^\\p{L}])(?:оренд\\p{L}*|аренд\\p{L}*|здам|сдам|rent(?:al)?)(?=$|[^\\p{L}])|" +
            "(?:частин\\p{L}*|частк\\p{L}*|часть|части|дол[яюи]|половин\\p{L}*)\\s*(?:\\S+\\s+){0,3}(?:квартир|будин|дом)|" +
            "(?:кімнат[ау]|комнат[ауы])\\s+(?:у|в)\\s+(?:\\S+\\s+){0,2}(?:квартир|будин|дом|гуртожит|общежит)|" +
            "(?:продаж(?:а)?|продам|продаю|прода[єеё]ться|прода[єеё]тся|продати|продать)\\s+(?:\\d+\\s+)?(?:кімнат[ауиі]|комнат[ауые])(?=$|[^\\p{L}])|" +
            "(?:за|/|per)\\s*(?:м[²2]|кв\\.?\\s*м|sq\\.?\\s*m|квадратн\\p{L}*\\s+метр)")) return true;
        String fraction = "(\\d+(?:[.,]\\d+)?)(?:\\s*/\\s*(\\d+(?:[.,]\\d+)?)|\\s*%)";
        String housing = "(?:квартир\\p{L}*|будин\\p{L}*|дом(?:а|у|ом|ів|ы)?)(?=$|[^\\p{L}])";
        Matcher before = Pattern.compile("(?iu)" + fraction + "\\s*(?:(?:част\\p{L}*|дол\\p{L}*|права|власності|собственности|у|в|від|от|на)\\s+){0,4}" + housing).matcher(text);
        while (before.find()) if (share(before)) return true;
        Matcher after = Pattern.compile("(?iu)" + housing + "\\s*[,;:–—-]?\\s*" + fraction + "(?=$|[^\\p{L}])").matcher(text);
        while (after.find())
            if (share(after) && !contains(text.substring(after.end()), "^\\s*(?:пов\\p{L}*|этаж\\p{L}*|етаж\\p{L}*)")) return true;
        return false;
    }

    private static boolean share(Matcher m) {
        double amount = Double.parseDouble(m.group(1).replace(',', '.'));
        return amount > 0 && amount < (m.group(2) == null ? 100 : Double.parseDouble(m.group(2).replace(',', '.')));
    }

    private static void parameters(Element container, JSONObject record) {
        String[][] specs = {
            {"(?:Поверх|Этаж)", "floor", "-?\\d{1,4}", "-10", "1000"},
            {"(?:Поверховість|Этажность)", "floorsTotal", "\\d{1,4}", "1", "1000"},
            {"(?:Загальна площа|Общая площадь)", "area", "(\\d+(?:[.,]\\d+)?)\\s*м[²2]", "0", "10000000"},
            {"(?:Кількість кімнат|Количество комнат)", "rooms", "(\\d{1,3})(?:\\s*(?:кімнат[аи]?|комнат[аы]?))?", "1", "1000"}
        };
        for (Element node : container.select("p")) for (String[] spec : specs) {
            Matcher label = Pattern.compile("(?iu)^" + spec[0] + ":\\s*(.*)$").matcher(Sources.text(node));
            if (!label.matches()) continue;
            Matcher value = Pattern.compile("(?iu)^" + spec[2] + "$").matcher(label.group(1));
            if (!value.matches()) throw failure("характеристики объекта не распознаны");
            double number = Double.parseDouble((value.groupCount() > 0 ? value.group(1) : value.group()).replace(',', '.'));
            if (!Double.isFinite(number) || number < Double.parseDouble(spec[3]) || number > Double.parseDouble(spec[4]) ||
                (spec[1].equals("area") && number <= 0) ||
                (record.has(spec[1]) && record.optDouble(spec[1]) != number))
                throw failure("характеристики объекта противоречивы");
            Json.put(record, spec[1], number);
        }
        if (record.has("floor") && record.has("floorsTotal") && record.optDouble("floor") > record.optDouble("floorsTotal"))
            throw failure("этаж выше этажности дома");
    }
}

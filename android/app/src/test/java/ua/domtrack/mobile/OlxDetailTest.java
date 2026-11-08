package ua.domtrack.mobile;

import static org.junit.Assert.*;
import java.util.*;
import org.json.*;
import org.junit.Test;

public class OlxDetailTest {
    static final String URL = "https://www.olx.ua/d/uk/obyavlenie/training-apartment-IDabc123.html";
    static final String CATALOG = "https://www.olx.ua/uk/nedvizhimost/kvartiry/prodazha-kvartir/zaporozhe/";

    // Synthetic public layout, with no real seller or contact details.
    static String fixture() {
        return "<html><head><link rel='canonical' href='" + URL + "'></head><body>" +
            "<nav data-testid='breadcrumbs'><a href='/uk/nedvizhimost/'>Нерухомість</a>" +
            "<a href='" + CATALOG + "'>Продаж квартир - Запоріжжя</a>" +
            "<a href='" + CATALOG + "?search[district_id]=123'>Продаж квартир - Навчальний район</a></nav>" +
            "<section data-testid='offer_title'><h4>Продам двокімнатну квартиру</h4></section>" +
            "<section data-testid='ad-price-container'><h3>14 500 $</h3></section>" +
            "<section data-testid='ad-parameters-container'><p>Поверх: 4</p><p>Поверховість: 5</p>" +
            "<p>Загальна площа: 46 м²</p><p>Площа кухні: 7 м²</p><p>Кількість кімнат: 2 кімнати</p></section>" +
            "<section data-testid='ad_description'>Навчальний опис квартири.</section>" +
            "<img src='https://images.example.test/must-not-load.jpg'>" +
            "<script>throw new Error('must not run');</script></body></html>";
    }

    private static JSONObject parse(String html, String url) {
        List<JSONObject> rows = Sources.structured(html, url);
        assertEquals(1, rows.size());
        return rows.get(0);
    }

    private static void rejected(String html) {
        assertThrows(IllegalArgumentException.class, () -> parse(html, URL));
    }

    @Test public void visibleDetailImportsScopedDisplayPriceAndFactsWithoutPhotos() {
        JSONObject r = parse(fixture(), URL);
        assertEquals(URL, r.optString("url"));
        assertEquals("abc123", r.optString("externalId"));
        assertEquals("olx", r.optString("source"));
        assertEquals("Запоріжжя", r.optString("city"));
        assertEquals("Запорізька", r.optString("region"));
        assertEquals("apartment", r.optString("kind"));
        assertEquals("sale", r.optString("deal"));
        assertEquals("total", r.optString("period"));
        assertEquals("display", r.optString("priceEvidence"));
        assertEquals("detail", r.optString("observationKind"));
        assertFalse(r.optBoolean("structured", true));
        assertEquals(14500, r.optDouble("price"), 0);
        assertEquals("USD", r.optString("currency"));
        assertEquals(4, r.optInt("floor"));
        assertEquals(5, r.optInt("floorsTotal"));
        assertEquals(46, r.optDouble("area"), 0);
        assertEquals(2, r.optInt("rooms"));
        for (String key : List.of("image", "photos", "contactPhone", "contactEmail", "latitude", "longitude"))
            assertFalse(key, r.has(key));
    }

    @Test public void trackingAndOfficialHostVariantsBindToExactCanonicalPath() {
        for (String request : List.of(URL + "?search_reason=search%7Corganic&utm_source=share", URL.replace("www.", "m."), URL.replace("www.", "")))
            assertEquals(URL, parse(fixture(), request).optString("url"));
    }

    @Test public void canonicalIsUniqueInHeadAndMatchesExactPathIdAndOfficialHost() {
        for (String html : List.of(
            fixture().replace("href='" + URL + "'", "href='" + URL.replace("abc123", "other123") + "'"),
            fixture().replace("href='" + URL + "'", "href='" + URL.replace("training-apartment", "other-slug") + "'"),
            fixture().replace("href='" + URL + "'", "href='" + URL.replace("www.olx.ua", "evil.example") + "'"),
            fixture().replace("href='" + URL + "'", "href='" + URL + "?price=1'"),
            fixture().replace("</head>", "<link rel='canonical' href='" + URL + "'></head>"),
            fixture().replace("rel='canonical'", "rel='alternate'"),
            fixture().replace("<head><link", "<head></head><body><link")
        )) rejected(html);
    }

    @Test public void refusesUnsafeOfficialAndLookalikeDetailUrls() {
        for (String url : List.of(URL.replace("https:", "http:"), URL.replace("www.olx.ua", "olx.ua.evil.example"), URL + "#price", URL.replace("www.olx.ua", "user@www.olx.ua"), URL.replace("www.olx.ua", "www.olx.ua:8443"), URL.replace("training-apartment", "training%2fapartment")))
            assertThrows(url, IllegalArgumentException.class, () -> parse(fixture(), url));
    }

    @Test public void requiresVerifiedCityAndSaleKindFromBreadcrumbLinks() {
        for (String html : List.of(
            fixture().replace("data-testid='breadcrumbs'", "data-testid='other'"),
            fixture().replace("zaporozhe/", "kiev/"),
            fixture().replace("Продаж квартир - Запоріжжя", "Продаж квартир - Київ"),
            fixture().replace("prodazha-kvartir", "arenda-kvartir").replace("Продаж квартир", "Оренда квартир"),
            fixture().replace("<nav ", "<nav hidden ")
        )) rejected(html);
        String house = fixture().replace("kvartiry/prodazha-kvartir", "doma/prodazha-domov")
            .replace("Продаж квартир", "Продаж будинків").replace("Продам двокімнатну квартиру", "Продам будинок");
        assertEquals("house", parse(house, URL).optString("kind"));
    }

    @Test public void rejectsUnknownCurrencyCompetingPricesAndHiddenLayout() {
        for (String price : List.of("14 500 $ / 600 000 грн", "14 500 DOGE", "14 500", "0 $", "14 500 $ / м²"))
            rejected(fixture().replace("14 500 $", price));
        for (String html : List.of(
            fixture().replace("</h3>", "</h3><h3>15 000 $</h3>"),
            fixture().replace("</h3>", "</h3><p>600 000 грн</p>"),
            fixture().replace("<h3>", "<h3 hidden>"),
            fixture().replace("data-testid='offer_title'", "hidden data-testid='offer_title'"),
            fixture().replace("data-testid='ad-price-container'", "style='display:none' data-testid='ad-price-container'"))) rejected(html);
        assertEquals("UAH", parse(fixture().replace("14 500 $", "600 000 грн"), URL).optString("currency"));
    }

    @Test public void refusesRentRoomShareAreaAndPeriodicPricesAndInactiveNotices() {
        for (String title : List.of("Здам квартиру", "Сдам квартиру", "Оренда квартири", "Продам кімнату в квартирі", "Продажа комнаты", "Продам частину квартири", "Продам 1/2 квартири", "Продам 50% квартири", "Продам квартиру 1/2", "Продам квартиру 50%"))
            rejected(fixture().replace("Продам двокімнатну квартиру", title));
        for (String extra : List.of("за місяць", "/ добу", "в месяц", "за м²"))
            rejected(fixture().replace("</h3>", "</h3><p>" + extra + "</p>"));
        rejected(fixture().replace("Навчальний опис квартири.", "Продається половина будинку."));
        rejected(fixture().replace("</body>", "<p>Оголошення більше не активне</p></body>"));
        assertEquals(14500, parse(fixture().replace("Продам двокімнатну квартиру", "Продам квартиру 4/5 поверх"), URL).optDouble("price"), 0);
    }

    @Test public void hiddenRecommendationsCannotSupplyOrConflictWithFacts() {
        JSONObject r = parse(fixture().replace("</body>", "<section hidden data-testid='ad-price-container'><h3>1 €</h3></section><aside><p>Поверх: 99</p><p>Загальна площа: 999 м²</p><p>Оренда квартир</p></aside></body>"), URL);
        assertEquals(4, r.optInt("floor"));
        assertEquals(46, r.optDouble("area"), 0);
        assertEquals(14500, r.optDouble("price"), 0);
    }

    @Test public void refusesContradictoryOrInvalidExplicitParameters() {
        for (String html : List.of(
            fixture().replace("<p>Поверх: 4</p>", "<p>Поверх: 4</p><p>Поверх: 3</p>"),
            fixture().replace("Поверх: 4", "Поверх: 6"),
            fixture().replace("Загальна площа: 46 м²", "Загальна площа: невідома"),
            fixture().replace("Кількість кімнат: 2 кімнати", "Кількість кімнат: 0"),
            fixture().replace("<p>Загальна площа: 46 м²</p>", "<p>Загальна площа: 46 м²</p><p>Загальна площа: 50 м²</p>"))) rejected(html);
        JSONObject missing = parse(fixture().replace("<p>Загальна площа: 46 м²</p>", ""), URL);
        assertFalse(missing.has("area"));
    }

    @Test public void denialsAndStructuredRecommendationsCannotStandInForDetail() {
        for (String html : List.of("<html>Access denied</html>", fixture().replace("data-testid='offer_title'", "data-testid='recommendation-title'"), fixture().replace("data-testid='ad-parameters-container'", "data-testid='other'"))) rejected(html);
        String recommendation = "<script type='application/ld+json'>{\"@type\":\"Apartment\",\"name\":\"Other listing\",\"offers\":{\"businessFunction\":\"Sell\",\"price\":1,\"priceCurrency\":\"USD\"},\"address\":{\"addressLocality\":\"Запоріжжя\"}}</script>";
        rejected(recommendation);
    }

    @Test public void htmlVisibilityAndCanonicalTokensCannotHideConflictingEvidence() {
        rejected(fixture().replace("<h3>", "<h3 aria-hidden='TRUE'>"));
        rejected(fixture().replace("</head>", "<link rel='alternate canonical' href='" + URL.replace("abc123", "other123") + "'></head>"));
        rejected(fixture().replace("</head>", "<link rel='CANONICAL' href='" + URL.replace("abc123", "other123") + "'></head>"));
    }

    @Test public void hiddenCanonicalMetadataAndExplicitRoomOffersAreNotWholeSaleEvidence() {
        rejected(fixture().replace("</head>", "<link hidden rel='canonical' href='" + URL.replace("abc123", "other123") + "'></head>"));
        for (String title : List.of("Продається кімната", "Продается комната", "Кімната 19 м²", "Комната"))
            rejected(fixture().replace("Продам двокімнатну квартиру", title));
    }

    @Test public void retainedDetailBehindVisibleDenialOrCaptchaCannotBeImported() {
        for (String notice : List.of("Access denied", "Please complete CAPTCHA", "Доступ заборонено", "Доступ ограничен"))
            rejected(fixture().replace("</body>", "<section>" + notice + "</section></body>"));
        assertEquals(14500, parse(fixture().replace("</body>", "<section hidden>Access denied</section></body>"), URL).optDouble("price"), 0);
    }

    @Test public void missingFactsAreKeptUnknownAndPageSizeIsBounded() {
        JSONObject row = parse(fixture().replace("<p>Поверх: 4</p>", "").replace("<p>Поверховість: 5</p>", "").replace("<p>Кількість кімнат: 2 кімнати</p>", ""), URL);
        for (String field : List.of("floor", "floorsTotal", "rooms")) assertFalse(field, row.has(field));
        rejected(fixture().replace("</body>", "x".repeat(2 * 1024 * 1024) + "</body>"));
        rejected(fixture().replace("</body>", "<i></i>".repeat(100001) + "</body>"));
        rejected(fixture().replace("Продам двокімнатну квартиру", "Продам ½ квартири"));
    }
}

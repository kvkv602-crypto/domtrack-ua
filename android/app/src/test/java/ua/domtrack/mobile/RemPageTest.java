package ua.domtrack.mobile;

import static org.junit.Assert.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.jsoup.Jsoup;

public class RemPageTest {
    static final String BASE = "https://rem.ua/prodazha-kvartir-zaporozhe";
    static final String URL = "https://rem.ua/prodazha-kvartira-2-komn-46-kv-m-zaporozhe-voznesenovskiy-rayon-db1234567";

    static Sources.Section section(String kind) {
        return new Sources.Section("rem", "Запоріжжя", "Запорізька", kind,
            kind.equals("house") ? "https://rem.ua/prodazha-domov-zaporozhe" : BASE);
    }

    static String card(String url, String title, String price) {
        return "<div class='object-item object-map-marker'><a class='object_block-link' href='" + url + "'></a>" +
            "<div class='object-item__content'><div class='object-item__img'><img data-src='https://storage.rem.ua/front.jpg'></div>" +
            "<a href='" + url + "'><h3 class='object-address'>" + title + "</h3></a>" +
            "<p class='object-city-region'>2-комн., 46.5 кв. м., Вознесенівський,</p>" +
            "<div class='object-card-description'>Світла квартира з балконом.</div>" +
            "<p class='object-price'>" + price + "</p><p class='object-price-per-meter'>333 $ за м²</p>" +
            "<div class='object-param'><div class='object-param__label'>поверх</div><div class='object-param__value'>4 з 5</div></div>" +
            "<div class='object-chips'><div class='object-chips__item'>Кімнат 2</div><div class='object-chips__item'>46.5/ 7 м²</div></div>" +
            "</div><div class='object_coords' style='display:none'><div class='object_lg'>35.09867</div>" +
            "<div class='object_lt'>47.82732</div></div></div>";
    }

    static String catalog(String cards, int page, int total, boolean next) {
        String query = page == 1 ? "" : "?page=" + page;
        return "<html><head><link rel='canonical' href='" + BASE.replace("rem.ua/", "rem.ua/ua/") + query + "'></head><body>" +
            "<div class='container-objects-counter'><p class='objects-counter'><span>" + total + "</span> об'єктів</p></div>" + cards +
            "<ul class='pagination'><li class='active'><span>" + page + "</span></li>" +
            (next ? "<li><a rel='next' href='" + BASE + "?page=" + (page + 1) + "'>Далі</a></li>" : "") +
            "</ul></body></html>";
    }

    private static Sources.Result parse(String html, int page) {
        return Sources.parse(html, section("apartment").page(page), section("apartment"), page);
    }

    @Test public void exactHomepageRoutesAreRegisteredWithoutGuessingUnsupportedCities() {
        List<Sources.Section> sections = Sources.sections("Запорожье", "both").stream().filter(s -> s.source.equals("rem")).toList();
        assertEquals(2, sections.size());
        assertEquals(BASE, sections.get(0).base);
        assertEquals("https://rem.ua/prodazha-domov-zaporozhe", sections.get(1).base);
        assertEquals("https://rem.ua/prodazha-kvartir-ivano_frankovsk", RemPage.section("Івано-Франківськ", "Івано-Франківська", "apartment").base);
        assertEquals("https://rem.ua/prodazha-domov-zhutomir", RemPage.section("Житомир", "Житомирська", "house").base);
        assertNull(RemPage.section("Луцьк", "Волинська", "apartment"));
        assertNull(RemPage.section("Ірпінь", "Київська", "house"));
    }

    @Test public void catalogImportsOnlyItsBoundPropertyAndKeepsDisplayPriceEvidence() {
        Sources.Result result = parse(catalog(card(URL, "Продам квартиру", "15 000 $"), 1, 26, true), 1);
        assertEquals(1, result.records.size());
        JSONObject row = result.records.get(0);
        assertEquals(URL, row.optString("url"));
        assertEquals("1234567", row.optString("externalId"));
        assertEquals("rem", row.optString("source"));
        assertEquals("Запоріжжя", row.optString("city"));
        assertEquals("apartment", row.optString("kind"));
        assertEquals(15000, row.optDouble("price"), 0);
        assertEquals("USD", row.optString("currency"));
        assertFalse(Model.verifiedPrice(row));
        assertEquals(2, row.optInt("rooms"));
        assertEquals(46.5, row.optDouble("area"), 0);
        assertEquals(4, row.optInt("floor"));
        assertEquals(5, row.optInt("floorsTotal"));
        assertEquals(47.82732, row.optDouble("latitude"), 0);
        assertEquals(35.09867, row.optDouble("longitude"), 0);
        assertEquals("source-unspecified", row.optString("locationAccuracy"));
        assertEquals(List.of("https://storage.rem.ua/front.jpg"), ListingPhotos.urls(row));
        assertFalse(result.end);
    }

    @Test public void totalPriceCurrencyIsReadFromItsOwnNodeInsteadOfPerMetreOrConvertedValues() {
        for (String[] price : new String[][] {{"15 000 $", "USD"}, {"15 000 €", "EUR"}, {"15 000 грн", "UAH"}}) {
            JSONObject row = parse(catalog(card(URL, "Продам квартиру", price[0]), 1, 1, false), 1).records.get(0);
            assertEquals(15000, row.optDouble("price"), 0);
            assertEquals(price[1], row.optString("currency"));
            assertEquals("display", row.optString("priceEvidence"));
        }
    }

    @Test public void rentOtherCitiesOtherKindsSharesAndUnitPricesCannotEnterSaleResults() {
        for (String invalid : List.of(
            card(URL.replace("prodazha", "arenda"), "Здам квартиру", "15 000 $"),
            card(URL.replace("zaporozhe", "kiev"), "Продам квартиру", "15 000 $"),
            card(URL.replace("-kv-m-zaporozhe-", "-kv-m-unknown-zaporozhe-street-"), "Продам квартиру", "15 000 $"),
            card(URL.replace("prodazha-kvartira", "prodazha-doma"), "Продам дом", "15 000 $"),
            card(URL, "Продам 1/2 квартири", "15 000 $"),
            card(URL, "Продам кімнату в квартирі", "15 000 $"),
            card(URL, "Продам квартиру", "15 000 $ за м²"),
            card(URL, "Продам квартиру", "15 000 $ / місяць"),
            card(URL, "Обмін квартири на автомобіль", "15 000 $"))) {
            assertThrows(IllegalArgumentException.class, () -> parse(catalog(invalid, 1, 1, false), 1));
        }
        String mixed = card(URL, "Продам квартиру", "15 000 $") + card(URL.replace("1234567", "7654321").replace("zaporozhe", "kiev"), "Продам квартиру", "1 000 $");
        assertEquals(1, parse(catalog(mixed, 1, 2, false), 1).records.size());
    }

    @Test public void wholeHousesRetainTheirFloorCountAndHalfHousesAreRejected() {
        String url = URL.replace("prodazha-kvartira-2-komn-46", "prodazha-doma-46");
        String card = card(url, "Продам будинок", "25 000 $").replace("2-комн., 46.5", "Дом, 46.5")
            .replace("поверх</div><div class='object-param__value'>4 з 5", "поверховий</div><div class='object-param__value'>2 -")
            .replace("Кімнат 2", "2 - поверховий");
        String html = catalog(card, 1, 1, false).replace("prodazha-kvartir", "prodazha-domov");
        JSONObject row = Sources.parse(html, section("house").base, section("house"), 1).records.get(0);
        assertEquals("house", row.optString("kind"));
        assertEquals(2, row.optInt("floorsTotal"));
        assertFalse(row.has("floor"));
        assertThrows(IllegalArgumentException.class, () -> Sources.parse(html.replace("Продам будинок", "Продам пол дома"), section("house").base, section("house"), 1));
    }

    @Test public void paginationMustMatchTheRequestedPageAndOnlyProvenFinalPagesFinish() {
        String one = card(URL, "Продам квартиру", "15 000 $");
        assertTrue(parse(catalog(one, 1, 1, false), 1).end);
        assertFalse(parse(catalog(one, 1, 26, false), 1).end);
        assertThrows(IllegalArgumentException.class, () -> parse(catalog(one, 1, 26, true), 2));
        assertThrows(IllegalArgumentException.class, () -> parse(catalog(one, 2, 26, false).replace("<span>2</span>", "<span>1</span>"), 2));
        assertThrows(IllegalArgumentException.class, () -> parse(catalog(one, 1, 26, true).replace("?page=2", "?page=3"), 1));
        String two = card(URL.replace("1234567", "7654321"), "Продам квартиру", "16 000 $");
        Sources.Result first = parse(catalog(one, 1, 26, true), 1), second = parse(catalog(two, 2, 26, false), 2);
        assertNotEquals(first.fingerprint, second.fingerprint);
        assertTrue(second.end);
    }

    @Test public void aSinglePageWithoutPagerRequiresCanonicalAndExactPublicTotal() {
        String one = card(URL, "Продам квартиру", "15 000 $");
        String html = catalog(one, 1, 1, false).replaceAll("<ul class='pagination'>.*?</ul>", "");
        assertEquals(1, parse(html, 1).records.size());
        assertTrue(parse(html, 1).end);
        assertThrows(IllegalArgumentException.class,
            () -> parse(catalog(one, 1, 26, false).replaceAll("<ul class='pagination'>.*?</ul>", ""), 1));
    }

    @Test public void conflictingCardIdentitiesPricesAndCanonicalSectionsAreRejected() {
        String card = card(URL, "Продам квартиру", "15 000 $");
        assertThrows(IllegalArgumentException.class, () -> parse(catalog(card.replace("</h3></a>", "</h3></a><a href='" + URL.replace("1234567", "7654321") + "'>Інша</a>"), 1, 1, false), 1));
        assertThrows(IllegalArgumentException.class, () -> parse(catalog(card.replace("<p class='object-price-per-meter'>", "<p class='object-price'>16 000 $</p><p class='object-price-per-meter'>"), 1, 1, false), 1));
        assertThrows(IllegalArgumentException.class, () -> parse(catalog(card, 1, 1, false).replace("rel='canonical' href='https://rem.ua/ua/", "rel='canonical' href='https://evil.example/"), 1));
        assertThrows(IllegalArgumentException.class, () -> parse("<html>Access denied</html>", 1));
    }

    static String detail() {
        return "<html><head><link rel='canonical' href='" + URL.replace("rem.ua/", "rem.ua/ua/") + "'></head><body>" +
            "<div class='object-info'><div class='object-info__price'>15 000 $</div><div class='object-info__street'>Тестова, 12</div></div>" +
            "<div class='description-container'><h1 class='description-container__title'>Продаж квартири 2-комн., 46.5 кв. м.</h1>" +
            "<div class='description-container__desc'>Світла квартира з балконом.</div></div>" +
            "<ul class='all-object-characteristic'><li><span class='name'>Кімнат:</span><span class='value'>2</span></li>" +
            "<li><span class='name'>Загальна площа:</span><span class='value'>46.5 м²</span></li>" +
            "<li><span class='name'>Поверх:</span><span class='value'>4</span></li>" +
            "<li><span class='name'>Поверховість:</span><span class='value'>5</span></li></ul>" +
            "<div id='object-slider'><object-slider :images='[&quot;https://storage.rem.ua/front.jpg&quot;,&quot;https://storage.rem.ua/room.jpg&quot;]'></object-slider></div>" +
            "<script>var defaultCoordinates={lat:30.5238,lng:50.45466};</script>" +
            "<script>defaultCoordinates.lat = '35.09867 '; defaultCoordinates.lng = '47.82732 ';</script>" +
            "<footer><a href='tel:+380000000000'>REM support</a></footer></body></html>";
    }

    @Test public void boundDetailImportAndWatchUseTheSameRemIdentityAndGallery() {
        JSONObject row = Sources.structured(detail(), URL).get(0);
        assertEquals(URL, row.optString("url"));
        assertEquals("rem", row.optString("source"));
        assertEquals("1234567", row.optString("externalId"));
        assertTrue(row.optBoolean("structured"));
        assertEquals("detail", row.optString("observationKind"));
        assertEquals("Тестова, 12", row.optString("address"));
        assertEquals(46.5, row.optDouble("area"), 0);
        assertEquals(4, row.optInt("floor"));
        assertEquals(5, row.optInt("floorsTotal"));
        assertEquals(47.82732, row.optDouble("latitude"), 0);
        assertEquals(35.09867, row.optDouble("longitude"), 0);
        assertFalse(row.has("contactPhone"));
        assertEquals(2, ListingPhotos.urls(row).size());
        assertEquals(URL, Sources.watchUrl(row));
        JSONObject watched = Sources.watchRecords(detail().replace("15 000 $", "14 000 $"), URL.replace("rem.ua/", "rem.ua/ua/"), row).get(0);
        assertEquals(Model.identity(row), Model.identity(watched));
        assertEquals(14000, watched.optDouble("price"), 0);
        assertFalse(Model.verifiedPrice(watched));
    }

    @Test public void unboundDetailAndDefaultMapCentreNeverSupplyPropertyData() {
        assertThrows(IllegalArgumentException.class, () -> Sources.structured(detail().replace("db1234567'", "db7654321'"), URL));
        assertThrows(IllegalArgumentException.class, () -> Sources.structured(detail().replace("15 000 $", "500 $ / місяць"), URL));
        JSONObject row = Sources.structured(detail().replace("<script>defaultCoordinates.lat = '35.09867 '; defaultCoordinates.lng = '47.82732 ';</script>", ""), URL).get(0);
        assertFalse(row.has("latitude"));
        assertFalse(row.has("longitude"));
    }

    @Test public void onlyVisiblePropertyContactLinksCanSupplyContacts() {
        String html = detail().replace("<footer>", "<div class='object-wrapper-btns'>" +
            "<a href='tel:+380501234567'>Телефон агента</a><a href='mailto:agent@example.org'>Пошта</a></div><footer>");
        JSONObject row = Sources.structured(html, URL).get(0);
        assertEquals("+380501234567", row.optString("contactPhone"));
        assertEquals("agent@example.org", row.optString("contactEmail"));
        JSONObject hidden = Sources.structured(html.replace("class='object-wrapper-btns'", "class='object-wrapper-btns' hidden"), URL).get(0);
        assertFalse(hidden.has("contactPhone"));
        assertFalse(hidden.has("contactEmail"));
    }
}

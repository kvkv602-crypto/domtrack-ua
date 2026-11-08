package ua.domtrack.mobile;

import static org.junit.Assert.*;
import java.util.*;
import org.json.*;
import org.junit.Test;

public class ListingWatchTest {
    private static JSONObject olxPrevious() {
        JSONObject row = Sources.structured(OlxDetailTest.fixture(), OlxDetailTest.URL).get(0);
        Json.put(row, "url", OlxDetailTest.URL.replace("www.", "m.") + "?search_reason=old-share");
        Json.put(row, "price", 20000);
        Json.put(row, "catalogUrl", OlxDetailTest.CATALOG);
        return row;
    }

    @Test public void sharedDetailImportKeepsDisplayEvidenceAndVerifiesResponseIdentity() {
        List<JSONObject> rows = Sources.importRecords(OlxDetailTest.fixture(), OlxDetailTest.URL + "?reason=share", OlxDetailTest.URL);
        assertEquals(1, rows.size());
        assertEquals("display", rows.get(0).optString("priceEvidence"));
        assertFalse(rows.get(0).optBoolean("structured", true));
        assertThrows(IllegalArgumentException.class, () -> Sources.importRecords(
            OlxDetailTest.fixture(), OlxDetailTest.URL, OlxDetailTest.URL.replace("abc123", "other123")));
        assertThrows(IllegalArgumentException.class, () -> Sources.importRecords(
            OlxDetailTest.fixture(), OlxDetailTest.URL, OlxDetailTest.URL.replace("training-apartment", "other-slug")));
    }

    @Test public void olxWatchFetchesDetailInsteadOfPreviousCatalog() {
        assertEquals(OlxDetailTest.URL.replace("www.", "m."), Sources.watchUrl(olxPrevious()));
        JSONObject bad = olxPrevious();
        Json.put(bad, "url", "https://olx.ua.evil.example/ad");
        assertThrows(IllegalArgumentException.class, () -> Sources.watchUrl(bad));
    }

    @Test public void olxWatchPreservesStoredIdentityWhileUsingFreshPrice() {
        JSONObject previous = olxPrevious();
        List<JSONObject> rows = Sources.watchRecords(OlxDetailTest.fixture(), OlxDetailTest.URL, previous);
        assertEquals(1, rows.size());
        assertEquals(previous.optString("url"), rows.get(0).optString("url"));
        assertEquals(Model.identity(previous), Model.identity(rows.get(0)));
        assertEquals(14500, rows.get(0).optDouble("price"), 0);
        assertEquals("display", rows.get(0).optString("priceEvidence"));
        assertEquals(20000, previous.optDouble("price"), 0);
    }

    @Test public void legacySharedOlxRowsKeepTheirIdentityAndAggregatorHistoriesStaySeparate() {
        JSONObject legacy = olxPrevious();
        Json.put(legacy, "source", "www.olx.ua");
        Json.put(legacy, "structured", true);
        assertEquals(OlxDetailTest.URL.replace("www.", "m."), Sources.watchUrl(legacy));
        List<JSONObject> rows = Sources.watchRecords(OlxDetailTest.fixture(), OlxDetailTest.URL, legacy);
        assertEquals(Model.identity(legacy), Model.identity(rows.get(0)));
        assertEquals(14500, rows.get(0).optDouble("price"), 0);
        JSONObject aggregator = olxPrevious();
        Json.put(aggregator, "source", "flatfy");
        Json.put(aggregator, "catalogUrl", "https://flatfy.ua/uk/продаж-квартир-запоріжжя");
        assertEquals("https://flatfy.ua/uk/продаж-квартир-запоріжжя", Sources.watchUrl(aggregator));
    }

    @Test public void missingOrDifferentTargetNeverBecomesSuccessfulWatch() {
        JSONObject previous = olxPrevious();
        for (String html : List.of("<html>Access denied</html>", OlxDetailTest.fixture().replace("abc123", "other123")))
            assertThrows(IllegalArgumentException.class, () -> Sources.watchRecords(html, OlxDetailTest.URL, previous));
        assertThrows(IllegalArgumentException.class, () -> Sources.watchRecords(OlxDetailTest.fixture(), OlxDetailTest.CATALOG, previous));
        assertThrows(IllegalArgumentException.class, () -> Sources.watchRecords(OlxDetailTest.fixture(), OlxDetailTest.URL.replace("training-apartment", "new-slug"), previous));
        JSONObject otherCity = Json.copy(previous);
        Json.put(otherCity, "city", "Київ");
        assertThrows(IllegalArgumentException.class, () -> Sources.watchRecords(OlxDetailTest.fixture(), OlxDetailTest.URL, otherCity));
        JSONObject otherKind = Json.copy(previous);
        Json.put(otherKind, "kind", "house");
        assertThrows(IllegalArgumentException.class, () -> Sources.watchRecords(OlxDetailTest.fixture(), OlxDetailTest.URL, otherKind));
    }

    private static JSONObject rieltorPrevious() {
        return Json.object("url", "https://rieltor.ua/zaporozhje/flats-sale/view/11111/", "source", "rieltor",
            "title", "Навчальна квартира", "city", "Запоріжжя", "kind", "apartment", "deal", "sale",
            "price", 9000, "currency", "USD", "catalogUrl", "https://rieltor.ua/zaporozhje/flats-sale/");
    }

    private static String card(String id, String price) {
        return "<div class='catalog-card'><a class='catalog-card-media' href='/zaporozhje/flats-sale/view/" + id + "/'></a>" +
            "<div class='catalog-card-region'><a href='/zaporozhje/flats-sale/'>Запоріжжя</a></div>" +
            "<p class='catalog-card-address'>Навчальна квартира</p><p class='catalog-card-price-title'>" + price + " $</p></div>";
    }

    @Test public void catalogWatchReturnsOnlyItsTargetAndRejectsCatalogWithoutIt() {
        JSONObject previous = rieltorPrevious();
        String catalog = "<div class='catalog-items-container'>" + card("11111", "10000") + card("22222", "1") + "</div>";
        List<JSONObject> rows = Sources.watchRecords(catalog, previous.optString("catalogUrl"), previous);
        assertEquals(1, rows.size());
        assertEquals(previous.optString("url"), rows.get(0).optString("url"));
        assertEquals(10000, rows.get(0).optDouble("price"), 0);
        JSONObject second = Json.copy(previous);
        Json.put(second, "url", "https://rieltor.ua/zaporozhje/flats-sale/view/22222/");
        List<JSONObject> otherRows = Sources.watchRecords(catalog, previous.optString("catalogUrl"), second);
        assertEquals(1, otherRows.size());
        assertEquals(second.optString("url"), otherRows.get(0).optString("url"));
        assertEquals(1, otherRows.get(0).optDouble("price"), 0);
        assertThrows(IllegalArgumentException.class, () -> Sources.watchRecords(
            "<div class='catalog-items-container'>" + card("22222", "1") + "</div>", previous.optString("catalogUrl"), previous));
        assertThrows(IllegalArgumentException.class, () -> Sources.watchRecords(
            "<div class='catalog-items-container'>" + card("11111", "10000") + card("11111", "11000") + "</div>", previous.optString("catalogUrl"), previous));
    }

    @Test public void structuredWatchCannotBorrowAnotherStructuredObject() {
        JSONObject previous = rieltorPrevious();
        Json.put(previous, "structured", true);
        String html = "<script type='application/ld+json'>{\"@type\":\"Apartment\",\"name\":\"Other listing\",\"offers\":{\"businessFunction\":\"Sell\",\"price\":1,\"priceCurrency\":\"USD\"},\"address\":{\"addressLocality\":\"Запоріжжя\"}}</script>";
        assertThrows(IllegalArgumentException.class, () -> Sources.watchRecords(html, "https://rieltor.ua/zaporozhje/flats-sale/view/22222/", previous));
    }

    @Test public void matchingWatchRowsMustNotConflictOrMutatePreviousInput() {
        JSONObject previous = olxPrevious();
        JSONObject first = Sources.structured(OlxDetailTest.fixture(), OlxDetailTest.URL).get(0);
        JSONObject conflicting = Json.copy(first);
        Json.put(conflicting, "price", 15000);
        assertThrows(IllegalArgumentException.class, () -> ListingLinks.watched(List.of(first, conflicting), previous));
        JSONObject wrong = Json.copy(first);
        Json.put(wrong, "url", OlxDetailTest.URL.replace("abc123", "other123"));
        assertThrows(IllegalArgumentException.class, () -> ListingLinks.watched(List.of(wrong), previous));
        assertEquals(20000, previous.optDouble("price"), 0);
    }
}

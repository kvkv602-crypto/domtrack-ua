package ua.domtrack.mobile;

import static org.junit.Assert.*;

import java.util.*;
import org.json.*;
import org.junit.Test;

public class CoreTest {

    private JSONObject listing() {
        return Json.object(
            "url",
            "https://example.org/flat/123",
            "title",
            "Квартира у парка",
            "city",
            "Запоріжжя",
            "kind",
            "apartment",
            "deal",
            "sale",
            "price",
            12000,
            "currency",
            "USD",
            "area",
            50,
            "rooms",
            2,
            "floor",
            4,
            "floorsTotal",
            9
        );
    }

    private JSONObject quote() {
        return Json.object(
            "date",
            Model.date(),
            "rates",
            Json.object("UAH", 1, "USD", 40, "EUR", 50)
        );
    }

    @Test
    public void emptyBudgetWorksInAllCurrencies() {
        for (String currency : List.of("USD", "EUR", "UAH"))
            assertTrue(Model.matches(listing(), Json.object("currency", currency), null));
    }

    @Test
    public void euroBudgetUsesDatedNbuRate() {
        assertTrue(
            Model.matches(listing(), Json.object("priceMax", 10000, "currency", "EUR"), quote())
        );
        assertFalse(
            Model.matches(listing(), Json.object("priceMax", 9000, "currency", "EUR"), quote())
        );
        JSONObject old = quote();
        Json.put(old, "date", "2020-01-01");
        assertFalse(
            Model.matches(listing(), Json.object("priceMax", 10000, "currency", "EUR"), old)
        );
    }

    @Test
    public void priceRiseAndDropBothProducePriceEvents() {
        JSONObject before = listing(),
            next = listing();
        Json.put(next, "price", 13000);
        assertTrue(Model.eventTypes(before, next, false, null, quote()).contains("price"));
        Json.put(next, "price", 11000);
        assertTrue(Model.eventTypes(before, next, false, null, quote()).contains("price"));
        assertTrue(Model.eventTypes(before, before, true, null, quote()).isEmpty());
    }

    @Test
    public void exchangeRateChangeAloneIsNotSellerPriceChange() {
        assertTrue(Model.eventTypes(listing(), listing(), false, null, quote()).isEmpty());
    }

    @Test
    public void thresholdCrossingUsesWatchCurrency() {
        JSONObject next = listing();
        Json.put(next, "price", 11000);
        assertTrue(
            Model.eventTypes(
                listing(),
                next,
                true,
                Json.object("target", 9000, "currency", "EUR"),
                quote()
            ).contains("threshold")
        );
        assertFalse(
            Model.eventTypes(
                listing(),
                next,
                true,
                Json.object("target", 8000, "currency", "EUR"),
                quote()
            ).contains("threshold")
        );
    }

    @Test
    public void fieldChangesNotifyOnlyWatchedObjects() {
        JSONObject next = listing();
        Json.put(next, "floor", 5);
        assertTrue(Model.eventTypes(listing(), next, false, null, quote()).isEmpty());
        assertTrue(Model.eventTypes(listing(), next, true, null, quote()).contains("object"));
    }

    @Test
    public void apartmentWithoutRequiredFieldsDoesNotPassFilter() {
        assertFalse(Model.matches(listing(), Json.object("roomsMin", 3), quote()));
        assertTrue(Model.matches(listing(), Json.object("roomsMin", 2, "roomsMax", 2), quote()));
        JSONObject r = listing();
        r.remove("floor");
        assertFalse(Model.matches(r, Json.object("floorMin", 1), quote()));
    }

    @Test
    public void polygonNeverGuessesMissingLocation() throws Exception {
        JSONArray polygon = new JSONArray("[[47,35],[48,35],[48,36],[47,36]]");
        assertTrue(Model.inside(47.5, 35.5, polygon));
        assertFalse(Model.inside(49, 35.5, polygon));
        assertFalse(Model.matches(listing(), Json.object("polygon", polygon), quote()));
    }

    @Test
    public void priceParsingRejectsSquareMeterAndConflictingValues() {
        assertNull(Sources.price("400 $ / м²", false));
        assertNull(Sources.price("Договорная", false));
        assertEquals(11000, Sources.price("$⁠11 000", true).optDouble("price"), .001);
    }

    @Test
    public void backupIsAuthenticatedAndWrongPasswordFails() throws Exception {
        JSONObject data = Json.object("records", new JSONArray().put(listing()));
        byte[] encrypted = Backup.encrypt(data, "test-pass-1234".toCharArray());
        assertFalse(
            new String(encrypted, java.nio.charset.StandardCharsets.UTF_8).contains("Квартира")
        );
        assertEquals(
            data.toString(),
            Backup.decrypt(
                new String(encrypted, java.nio.charset.StandardCharsets.UTF_8),
                "test-pass-1234".toCharArray()
            ).toString()
        );
        try {
            Backup.decrypt(
                new String(encrypted, java.nio.charset.StandardCharsets.UTF_8),
                "wrong-password".toCharArray()
            );
            fail("Wrong password accepted");
        } catch (javax.crypto.AEADBadTagException expected) {}
    }

    @Test
    public void privateNetworksAreRejected() throws Exception {
        for (String ip : List.of(
            "127.0.0.1",
            "192.168.1.2",
            "10.0.0.1",
            "169.254.169.254",
            "100.64.0.1",
            "::1",
            "fd00::1"
        ))
            assertFalse(Network.isPublic(java.net.InetAddress.getByName(ip)));
        assertTrue(Network.isPublic(java.net.InetAddress.getByName("1.1.1.1")));
    }

    @Test
    public void robotsLongestRuleAndExplicitAllow() {
        assertFalse(Network.robots("User-agent: *\nDisallow: /private/", "/private/x"));
        assertTrue(
            Network.robots(
                "User-agent: *\nDisallow: /private/\nAllow: /private/public/",
                "/private/public/x"
            )
        );
    }

    @Test
    public void businessQueryAndHashIdsStaySeparate() {
        JSONObject a = listing(),
            b = listing();
        Json.put(a, "url", "https://example.org/ad?id=1");
        Json.put(b, "url", "https://example.org/ad?id=2");
        assertNotEquals(Model.identity(a), Model.identity(b));
        Json.put(b, "url", "https://example.org/ad?id=1&utm_source=search");
        assertEquals(Model.identity(a), Model.identity(b));
        Json.put(a, "url", "https://example.org/#/ad/1");
        Json.put(b, "url", "https://example.org/#/ad/2");
        assertNotEquals(Model.identity(a), Model.identity(b));
    }

    @Test
    public void cheapestConfirmedOfferUsesFxAndKeepsOriginals() {
        JSONObject a = listing(),
            b = listing();
        Json.put(a, "sameGroup", "confirmed-1");
        Json.put(b, "sameGroup", "confirmed-1");
        Json.put(b, "url", "https://other.example/2");
        Json.put(b, "price", 10000);
        Json.put(b, "currency", "EUR");
        List<JSONObject> r = Dedupe.collapsed(List.of(a, b), quote(), "EUR");
        assertEquals(1, r.size());
        assertEquals("USD", r.get(0).optString("currency"));
        assertEquals(2, r.get(0).optJSONArray("confirmedAlternatives").length());
        assertEquals(12000, a.optDouble("price"), .01);
    }

    @Test
    public void reorderedPhrasesRemainCandidatesWithDifferentPrices() {
        JSONObject a = listing(),
            b = listing();
        Json.put(a, "address", "вул. Перемоги, 12");
        Json.put(b, "address", "Перемоги вул., 12");
        Json.put(a, "title", "Терраса библиотека камин панорамный");
        Json.put(b, "title", "Панорамный камином библиотекой террасой");
        Json.put(b, "price", 20000);
        assertFalse(Dedupe.evidence(a, b, quote()).isEmpty());
    }

    @Test
    public void closePriceSupportsWeakerWordingOnlyAfterPhysicalChecks() {
        JSONObject a = listing(),
            b = listing();
        Json.put(a, "address", "Перемоги, 12");
        Json.put(b, "address", "Перемоги, 12");
        Json.put(a, "title", "Терраса библиотека камин");
        Json.put(b, "title", "Камином террасой библиотекой");
        Json.put(b, "price", 10000);
        Json.put(b, "currency", "EUR");
        assertFalse(Dedupe.evidence(a, b, quote()).isEmpty());
        assertTrue(Dedupe.evidence(a, b, null).isEmpty());
        Json.put(b, "floor", 5);
        assertTrue(Dedupe.evidence(a, b, quote()).isEmpty());
    }

    @Test
    public void equalPriceAndGenericTitleNeverIdentifyAnObject() {
        JSONObject a = listing(),
            b = listing();
        Json.put(a, "address", "Перемоги, 12");
        Json.put(b, "address", "Перемоги, 12");
        Json.put(a, "title", "Продажа квартиры");
        Json.put(b, "title", "Квартиры продажа");
        assertTrue(Dedupe.evidence(a, b, quote()).isEmpty());
    }

    @Test
    public void numericStreetNamesStaySeparate() {
        JSONObject a = listing(),
            b = listing();
        Json.put(a, "address", "40 лет Победы, 12");
        Json.put(b, "address", "50 лет Победы, 12");
        assertNotEquals(Dedupe.address(a), Dedupe.address(b));
    }

    @Test
    public void manualSearchUsesSelectedCityWhileBackgroundUsesSavedSearches() {
        List<JSONObject> saved = List.of(
            Json.object(
                "enabled",
                true,
                "filters",
                Json.object("city", "Запоріжжя", "kind", "both")
            )
        );
        List<Sources.Section> manual = Scanner.sectionsFor(
            saved,
            Json.object("city", "Київ", "kind", "house")
        );
        assertFalse(manual.isEmpty());
        assertTrue(manual.stream().allMatch(s -> s.city.equals("Київ") && s.kind.equals("house")));
        assertTrue(
            Scanner.sectionsFor(saved, null)
                .stream()
                .allMatch(s -> s.city.equals("Запоріжжя"))
        );
        assertTrue(
            Scanner.sectionsFor(
                List.of(Json.object("enabled", false, "filters", Json.object("city", "Київ"))),
                null
            ).isEmpty()
        );
    }
}

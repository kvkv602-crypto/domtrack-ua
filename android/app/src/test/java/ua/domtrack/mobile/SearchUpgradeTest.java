package ua.domtrack.mobile;

import static org.junit.Assert.*;

import java.util.*;
import org.json.*;
import org.junit.Test;

public class SearchUpgradeTest {

    @Test
    public void russianAndUkrainianCitiesAndDistrictsAreEquivalent() {
        assertEquals(Bilingual.city("Запорожье"), Bilingual.city("Запоріжжя"));
        assertEquals(Bilingual.city("Киев"), Bilingual.city("Київ"));
        assertTrue(
            Bilingual.contains("Шевченківський, вул. Брюллова, 16", "Шевченковский Брюллова")
        );
        assertFalse(Bilingual.contains("Дніпровський, вул. Брюллова, 16", "Шевченковский"));
        assertTrue(
            Sources.sections("Запорожье", "apartment")
                .stream()
                .anyMatch(s -> s.source.equals("domria-web"))
        );
    }

    @Test
    public void bilingualFullTextUsesWordsAndKeepsHouseNumbers() {
        assertTrue(
            Bilingual.contains(
                "Продається квартира, цегляний будинок, меблі",
                "продам квартира кирпичный дом мебель"
            )
        );
        assertFalse(Bilingual.contains("Перемоги 12", "Победы 13"));
    }

    @Test
    public void compactRoomAndFloorNotationsKeepParityWithDesktop() {
        JSONObject r = new JSONObject();
        Sources.params("2-комнатная|42 м²|4/5", r);
        assertEquals(2, r.optInt("rooms"));
        assertEquals(4, r.optInt("floor"));
        assertEquals(5, r.optInt("floorsTotal"));
        Sources.params("4 эт.", r);
        assertEquals(4, r.optInt("floor"));
        assertEquals(13100, Sources.price("13 100 $ торг", false).optDouble("price"), .001);
    }

    @Test
    public void rejectedCardsStillHaveDistinctPageFingerprints() {
        assertNotEquals(
            new Sources.Result(List.of(), false, 20, List.of("ad-1")).fingerprint,
            new Sources.Result(List.of(), false, 20, List.of("ad-2")).fingerprint
        );
    }

    @Test
    public void drawingRejectsSelfIntersectionAndAcceptsRectangle() throws Exception {
        assertTrue(Geometry.valid(new JSONArray("[[47,35],[48,35],[48,36],[47,36]]")));
        assertFalse(Geometry.valid(new JSONArray("[[47,35],[48,36],[47,36],[48,35]]")));
        assertFalse(Geometry.valid(new JSONArray("[[47,35],[47,35],[47,35]]")));
    }

    @Test
    public void movingWindowDoesNotFinishRieltorBeforePartialLastPage() {
        Sources.Section s = Sources.sections("Запоріжжя", "apartment").get(0);
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(
            "<span data-listing-count>747 оголошень</span><ul class=pagination_custom><li class=active><a>37</a></li></ul>"
        );
        assertFalse(Pagination.end(doc, s, 37, 20));
        assertTrue(Pagination.end(doc, s, 38, 7));
    }

    @Test
    public void repeatedFirstPageIsRejectedInsteadOfReportedComplete() {
        Sources.Section s = Sources.sections("Запоріжжя", "apartment")
            .stream()
            .filter(x -> x.source.equals("domria-web"))
            .findFirst()
            .orElseThrow();
        var doc = org.jsoup.Jsoup.parse(
            "<head><link rel=canonical href='" +
                s.base +
                "'></head><div class=pager><span class='page-item active'>1</span></div>"
        );
        assertThrows(IllegalArgumentException.class, () -> Pagination.verify(doc, s.page(2), s, 2));
    }

    @Test
    public void semanticCandidateCannotOverrideDifferentFloors() {
        JSONObject a = Json.object(
            "city",
            "Запоріжжя",
            "kind",
            "apartment",
            "address",
            "вул. Перемоги, 12",
            "rooms",
            2,
            "floor",
            4,
            "area",
            42
        );
        JSONObject b = Json.copy(a);
        Json.put(b, "city", "Запорожье");
        Json.put(b, "address", "Победы ул., 12");
        assertTrue(SemanticIndex.physical(a, b));
        Json.put(b, "floor", 5);
        assertFalse(SemanticIndex.physical(a, b));
    }

    @Test
    public void riaLinkedSchemasImportWholeFlatWithoutInventingCoordinates() {
        String url = "https://dom.ria.com/uk/realty-prodaja-kvartira-zaporoje-test-35044466.html";
        String html =
            "<script type='application/ld+json'>" +
            new JSONArray()
                .put(
                    Json.object(
                        "@type",
                        "Product",
                        "@id",
                        url,
                        "name",
                        "Продаж 2к квартири на вул. Брюллова 16 • ID 35044466",
                        "offers",
                        Json.object(
                            "businessFunction",
                            "sell",
                            "url",
                            url,
                            "price",
                            13100,
                            "priceCurrency",
                            "USD",
                            "areaServed",
                            "Запорожская обл., Запорожье, район Шевченковский"
                        )
                    )
                )
                .put(
                    Json.object(
                        "@type",
                        "Apartment",
                        "url",
                        url,
                        "numberOfRooms",
                        2,
                        "floorSize",
                        Json.object("value", 42)
                    )
                )
                .toString() +
            "</script><p>2 кімнати · 4 поверх з 5</p>";
        List<JSONObject> rows = Sources.structured(html, url);
        assertEquals(1, rows.size());
        JSONObject r = rows.get(0);
        assertEquals("Запоріжжя", r.optString("city"));
        assertEquals(4, r.optInt("floor"));
        assertEquals(5, r.optInt("floorsTotal"));
        assertFalse(r.has("latitude"));
        assertTrue(
            Model.matches(
                r,
                Json.object(
                    "city",
                    "Запорожье",
                    "district",
                    "Шевченківський",
                    "roomsMin",
                    2,
                    "floorMax",
                    4
                ),
                null
            )
        );
    }
}

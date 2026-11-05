package ua.domtrack.mobile;

import static org.junit.Assert.*;

import java.util.*;
import org.json.*;
import org.junit.Test;

public class NewSourcesTest {

    @Test
    public void repeatingSearchRefreshesFinishedSitesWhileAnotherSiteRemainsPartial() {
        JSONObject finished = Json.object("nextPage", 1, "complete", true),
            partial = Json.object("nextPage", 25, "complete", false, "fingerprint", "saved-cursor");
        assertFalse(Scanner.manualStart(finished).optBoolean("complete"));
        JSONObject resumed = Scanner.manualStart(partial);
        assertEquals(25, resumed.optInt("nextPage"));
        assertEquals("saved-cursor", resumed.optString("fingerprint"));
        assertTrue(finished.optBoolean("complete"));
    }

    private Sources.Section section(String source) {
        return Sources.sections("Запоріжжя", "apartment")
            .stream()
            .filter(s -> s.source.equals(source))
            .findFirst()
            .orElseThrow();
    }

    private JSONObject card(long id) {
        return Json.object(
            "id",
            id,
            "url_raw",
            "https://example.org/ad/" + id,
            "header",
            "Тестова вулиця, 16",
            "text",
            "Окрема квартира з балконом",
            "section_id",
            1,
            "price",
            11000,
            "currency",
            "USD",
            "room_count",
            2,
            "area_total",
            42,
            "floor",
            4,
            "floor_count",
            5,
            "is_owner",
            true,
            "group_page_status",
            "active",
            "geo_entities",
            Json.array(
                List.of(
                    Json.object("type", "city", "name", "Запоріжжя"),
                    Json.object("type", "district", "name", "Шевченківський")
                )
            ),
            "location",
            Json.array(List.of(35.1396, 47.8388))
        );
    }

    private String flatfy(JSONObject row, int active, boolean next) {
        Sources.Section s = section("flatfy");
        return (
            "<main><article class='realty-preview' id='" +
            row.optLong("id") +
            "'></article></main><a class='paging-button active-link' href='" +
            s.page(active) +
            "'>" +
            active +
            "</a>" +
            (next
                    ? "<a class='paging-nav--right paging-button' href='" +
                    s.page(active + 1) +
                    "'>Далі</a>"
                    : "") +
            "<script>window.INITIAL_STATE={\"realties\":{\"list\":[" +
            row +
            "]},\"unused\":undefined};</script>"
        );
    }

    @Test
    public void flatfyReadsExactPricesDimensionsAndLocationWithoutExecutingJavascript() {
        Sources.Section s = section("flatfy");
        Sources.Result result = Sources.parse(flatfy(card(123), 1, true), s.base, s, 1);
        assertFalse(result.end);
        assertEquals(1, result.records.size());
        JSONObject r = result.records.get(0);
        assertEquals(11000, r.optDouble("price"), 0);
        assertEquals("USD", r.optString("currency"));
        assertEquals(42, r.optDouble("area"), 0);
        assertEquals(4, r.optInt("floor"));
        assertEquals(5, r.optInt("floorsTotal"));
        assertEquals("owner", r.optString("sellerType"));
        assertEquals(47.8388, r.optDouble("latitude"), 1e-8);
        assertEquals(35.1396, r.optDouble("longitude"), 1e-8);
    }

    @Test
    public void flatfyPageTwoHasIndependentFingerprintAndVerifiedEnd() {
        Sources.Section s = section("flatfy");
        Sources.Result a = Sources.parse(flatfy(card(123), 1, true), s.base, s, 1),
            b = Sources.parse(flatfy(card(456), 2, false), s.page(2), s, 2);
        assertNotEquals(a.fingerprint, b.fingerprint);
        assertTrue(b.end);
    }

    @Test(expected = IllegalArgumentException.class)
    public void flatfyRejectsRepeatedFirstPage() {
        Sources.Section s = section("flatfy");
        Sources.parse(flatfy(card(123), 1, true), s.page(2), s, 2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void flatfyRejectsWrongCity() {
        JSONObject c = card(123);
        Json.put(
            c,
            "geo_entities",
            Json.array(List.of(Json.object("type", "city", "name", "Київ")))
        );
        Sources.Section s = section("flatfy");
        Sources.parse(flatfy(c, 1, false), s.base, s, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void flatfyRejectsRentSection() {
        JSONObject c = card(123);
        Json.put(c, "section_id", 2);
        Sources.Section s = section("flatfy");
        Sources.parse(flatfy(c, 1, false), s.base, s, 1);
    }

    @Test
    public void lunReadsFlightJsonAndNeverClaimsWholeCatalogFromFirstPage() {
        JSONObject snake = card(123),
            camel = Json.copy(snake);
        for (String[] pair : List.of(
            new String[] { "url_raw", "urlRaw" },
            new String[] { "section_id", "sectionId" },
            new String[] { "geo_entities", "geoEntities" },
            new String[] { "room_count", "roomCount" },
            new String[] { "area_total", "areaTotal" },
            new String[] { "floor_count", "floorCount" },
            new String[] { "is_owner", "isOwner" }
        ))
            Json.put(camel, pair[1], snake.opt(pair[0]));
        String chunk =
            "e:T5,Hello31:" +
            Json.array(
                List.of(
                    "$",
                    "component",
                    Json.object("realties", Json.object("cards", Json.array(List.of(camel))))
                )
            ) +
            "\n";
        String html =
            "<link rel='canonical' href='" +
            section("lun").base +
            "'><div class='RealtyCard-test__root'><button data-event-options='site:example.org|page_id:123'>Тест</button></div><script>self.__next_f.push(" +
            Json.array(List.of(1, chunk)) +
            ")</script>";
        Sources.Section s = section("lun");
        Sources.Result r = Sources.parse(html, s.base, s, 1);
        assertEquals(1, r.records.size());
        assertFalse(r.end);
    }

    @Test
    public void robotsQueryRestrictionsAndExplicitPaginationExceptionAreRespected() {
        String lun = "User-agent: *\nDisallow: /*?*\n# Allow: /*?page=\n";
        assertTrue(Network.robots(lun, "/sale/zp/flats"));
        assertFalse(Network.robots(lun, "/sale/zp/flats?page=2"));
        assertTrue(Network.robots(lun + "Allow: /*?page=\n", "/sale/zp/flats?page=2"));
    }

    @Test
    public void aggregatorPriceHistoriesStaySeparateButSameOriginalUrlCollapsesToCheapest() {
        JSONObject a = Json.object(
                "url",
                "https://example.org/ad/1",
                "source",
                "flatfy",
                "price",
                11000,
                "currency",
                "USD"
            ),
            b = Json.copy(a),
            original = Json.copy(a);
        Json.put(b, "source", "lun");
        Json.put(b, "price", 10500);
        Json.put(original, "source", "rieltor");
        Json.put(original, "price", 12000);
        assertNotEquals(Model.identity(a), Model.identity(b));
        assertNotEquals(Model.identity(a), Model.identity(original));
        List<JSONObject> grouped = Dedupe.collapsed(
            List.of(a, b, original),
            new JSONObject(),
            "USD"
        );
        assertEquals(1, grouped.size());
        assertEquals(10500, grouped.get(0).optDouble("price"), 0);
        assertEquals(3, grouped.get(0).optJSONArray("confirmedAlternatives").length());
    }

    @Test
    public void sevenDistinctSitesAreRegisteredForZaporizhzhia() {
        Set<String> sources = new HashSet<>();
        for (Sources.Section s : Sources.sections("Запорожье", "apartment")) sources.add(s.source);
        assertEquals(
            Set.of("rieltor", "domria-web", "obyava", "domik", "olx", "lun", "flatfy"),
            sources
        );
    }

    @Test
    public void olxAdapterDoesNotImportRecommendedCardsFromAnotherCity() {
        Sources.Section s = section("olx");
        String valid =
            "<div data-cy='l-card'><a href='/d/uk/obyavlenie/test-IDabc123.html'><h4>Продам квартиру</h4></a><p data-testid='ad-price'>11 000 $</p><p data-testid='location-date'>Запоріжжя, Центр - Сьогодні</p></div>";
        Sources.Result r = Sources.parse(
            valid + valid.replace("Запоріжжя", "Київ").replace("IDabc123", "IDxyz789"),
            s.base,
            s,
            1
        );
        assertEquals(1, r.records.size());
        assertFalse(r.end);
    }
}

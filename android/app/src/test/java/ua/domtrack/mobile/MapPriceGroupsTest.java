package ua.domtrack.mobile;

import static org.junit.Assert.*;

import java.util.*;
import org.json.JSONObject;
import org.junit.Test;

public class MapPriceGroupsTest {
    private MapPriceGroups.Entry entry(String id, float x, float y, double price, String currency) {
        return new MapPriceGroups.Entry(Json.object("id", id, "price", price, "currency", currency), x, y);
    }

    private List<MapPriceGroups.Group> layout(List<MapPriceGroups.Entry> entries) {
        return MapPriceGroups.layout(entries, 400, 600, 1, "USD", new JSONObject(), text -> text.length() * 7f);
    }

    @Test public void crowdedCoordinatesBecomeOneGroupWithoutLosingAnyMember() {
        List<MapPriceGroups.Group> groups = layout(List.of(
            entry("a", 150, 200, 23500, "USD"), entry("b", 152, 202, 31000, "USD"),
            entry("c", 149, 199, 29000, "USD")));
        assertEquals(1, groups.size());
        assertEquals(List.of("a", "b", "c"), ids(groups.get(0)));
        assertEquals("3 · от 23,5 тыс. USD", groups.get(0).label);
    }

    @Test public void sparseCoordinatesKeepIndividualPricesAndRealAnchors() {
        List<MapPriceGroups.Group> groups = layout(List.of(
            entry("a", 50, 100, 23500, "USD"), entry("b", 340, 500, 1050000, "USD")));
        assertEquals(2, groups.size());
        assertEquals("23,5 тыс. USD", groups.get(0).label);
        assertEquals("1,05 млн USD", groups.get(1).label);
        assertEquals(50, groups.get(0).anchorX, 0);
        assertEquals(100, groups.get(0).anchorY, 0);
        assertEquals(List.of("b"), ids(groups.get(1)));
    }

    @Test public void chipsAndTheirTouchAreasNeverOverlapAfterNewGroupsGrow() {
        ArrayList<MapPriceGroups.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 36; i++) entries.add(entry("id" + i, 20 + (i % 6) * 70,
            20 + (i / 6) * 100, 15000 + i * 1000, "USD"));
        List<MapPriceGroups.Group> groups = layout(entries);
        Set<String> retained = new HashSet<>();
        for (int i = 0; i < groups.size(); i++) {
            MapPriceGroups.Group a = groups.get(i);
            assertTrue(a.right - a.left >= 48);
            assertEquals(48, a.bottom - a.top, 0);
            for (MapPriceGroups.Entry entry : a.members) assertTrue(retained.add(entry.record.optString("id")));
            for (int j = i + 1; j < groups.size(); j++) {
                MapPriceGroups.Group b = groups.get(j);
                assertFalse("Touch areas collide", a.left < b.right && a.right > b.left &&
                    a.top < b.bottom && a.bottom > b.top);
            }
        }
        assertEquals(36, retained.size());
    }

    @Test public void edgeLabelsStayOnScreenAndOffscreenPointsDoNotCreateGroups() {
        List<MapPriceGroups.Group> groups = layout(List.of(
            entry("a", 0, 0, 23000, "USD"), entry("b", 400, 600, 47000, "USD"),
            entry("outside", -1, 300, 50000, "USD")));
        assertEquals(2, groups.size());
        for (MapPriceGroups.Group group : groups) {
            assertTrue(group.left >= 0 && group.top >= 0);
            assertTrue(group.right <= 400 && group.bottom <= 600);
        }
    }

    @Test public void groupingIsDeterministicWhenDatabaseOrderChanges() {
        List<MapPriceGroups.Entry> entries = new ArrayList<>(List.of(
            entry("c", 210, 200, 41000, "USD"), entry("a", 30, 100, 15000, "USD"),
            entry("d", 350, 500, 61000, "USD"), entry("b", 170, 200, 31000, "USD")));
        List<MapPriceGroups.Group> before = layout(entries);
        assertFalse(before.isEmpty());
        Collections.reverse(entries);
        List<MapPriceGroups.Group> after = layout(entries);
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(ids(before.get(i)), ids(after.get(i)));
            assertEquals(before.get(i).left, after.get(i).left, 0);
            assertEquals(before.get(i).top, after.get(i).top, 0);
        }
    }

    @Test public void aWiderGroupMergesAgainWhenItWouldCoverANeighbour() {
        List<MapPriceGroups.Group> groups = layout(List.of(entry("a", 100, 200, 10000, "USD"),
            entry("b", 110, 200, 10000, "USD"), entry("c", 220, 200, 10000, "USD")));
        assertEquals(1, groups.size());
        assertEquals(List.of("a", "b", "c"), ids(groups.get(0)));
    }

    @Test public void zoomingApartRevealsAllIndividualMembers() {
        assertEquals(1, layout(List.of(entry("a", 180, 200, 10000, "USD"),
            entry("b", 185, 205, 20000, "USD"))).size());
        List<MapPriceGroups.Group> zoomed = layout(List.of(entry("a", 30, 150, 10000, "USD"),
            entry("b", 350, 450, 20000, "USD")));
        assertEquals(2, zoomed.size());
        assertEquals(List.of("a"), ids(zoomed.get(0)));
        assertEquals(List.of("b"), ids(zoomed.get(1)));
    }

    @Test public void mixedCurrencyGroupShowsOnlyCountWithoutCurrentComparableRates() {
        List<MapPriceGroups.Entry> entries = List.of(entry("a", 100, 100, 25000, "USD"),
            entry("b", 101, 101, 800000, "UAH"));
        assertEquals("2 объекта", layout(entries).get(0).label);
        JSONObject stale = Json.object("date", "2000-01-01", "rates", Json.object("USD", 40, "UAH", 1));
        assertEquals("2 объекта", MapPriceGroups.layout(entries, 400, 600, 1, "USD", stale,
            text -> text.length() * 7f).get(0).label);
    }

    @Test public void minimumUsesRequestedCurrencyAndEveryMemberWithTodaysRates() {
        JSONObject quote = Json.object("date", Model.date(), "rates", Json.object("USD", 40, "UAH", 1, "EUR", 44));
        List<MapPriceGroups.Entry> entries = List.of(entry("a", 100, 100, 25000, "USD"),
            entry("b", 101, 101, 800000, "UAH"), entry("c", 102, 102, 22000, "EUR"));
        assertEquals("3 · от 20 тыс. USD", MapPriceGroups.layout(entries, 400, 600, 1,
            "USD", quote, text -> text.length() * 7f).get(0).label);
        assertEquals("3 · от 800 тыс. UAH", MapPriceGroups.layout(entries, 400, 600, 1,
            "UAH", quote, text -> text.length() * 7f).get(0).label);
    }

    @Test public void unconvertibleMemberCannotBeOmittedFromTheMinimum() {
        List<MapPriceGroups.Entry> entries = List.of(entry("a", 100, 100, 25000, "USD"),
            entry("b", 101, 101, 800000, "UAH"));
        JSONObject incomplete = Json.object("date", Model.date(), "rates", Json.object("USD", 40));
        assertEquals("2 объекта", MapPriceGroups.layout(entries, 400, 600, 1, "USD", incomplete,
            text -> text.length() * 7f).get(0).label);
    }

    @Test public void singletonFallsBackToItsOwnCurrencyWithoutInventingAConversion() {
        assertEquals("800 тыс. UAH", layout(List.of(entry("a", 100, 100, 800000, "UAH"))).get(0).label);
    }

    @Test public void cachedPricesRecalculateWhenARecordPriceOrCurrencyChanges() {
        JSONObject quote = Json.object("date", Model.date(), "rates", Json.object("USD", 40, "UAH", 1));
        MapPriceGroups.Pricing pricing = new MapPriceGroups.Pricing("USD", quote);
        List<MapPriceGroups.Entry> entries = List.of(entry("a", 100, 100, 25000, "USD"),
            entry("b", 101, 101, 800000, "UAH"));
        assertEquals("2 · от 20 тыс. USD", MapPriceGroups.layout(entries, 400, 600, 1, pricing,
            text -> text.length() * 7f).get(0).label);
        Json.put(entries.get(1).record, "price", 1200000);
        assertEquals("2 · от 25 тыс. USD", MapPriceGroups.layout(entries, 400, 600, 1, pricing,
            text -> text.length() * 7f).get(0).label);
        Json.put(entries.get(1).record, "currency", "USD");
        assertEquals("2 · от 25 тыс. USD", MapPriceGroups.layout(entries, 400, 600, 1, pricing,
            text -> text.length() * 7f).get(0).label);
        Json.put(entries.get(0).record, "currency", "UAH");
        assertEquals("2 · от 625 USD", MapPriceGroups.layout(entries, 400, 600, 1, pricing,
            text -> text.length() * 7f).get(0).label);
    }

    @Test public void emptyViewportOrNoPointsProducesNoMarkers() {
        assertTrue(layout(List.of()).isEmpty());
        assertTrue(MapPriceGroups.layout(List.of(entry("a", 1, 1, 12000, "USD")), 0, 0,
            1, "USD", new JSONObject(), text -> 20).isEmpty());
    }

    private List<String> ids(MapPriceGroups.Group group) {
        ArrayList<String> ids = new ArrayList<>();
        for (MapPriceGroups.Entry entry : group.members) ids.add(entry.record.optString("id"));
        return ids;
    }
}

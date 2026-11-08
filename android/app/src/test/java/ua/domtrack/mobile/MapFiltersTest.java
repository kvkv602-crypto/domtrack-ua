package ua.domtrack.mobile;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public class MapFiltersTest {

    private JSONObject search() throws Exception {
        return Json.object(
            "city", "Запоріжжя", "kind", "apartment", "currency", "USD",
            "priceMax", 15000, "roomsMin", 2, "query", "балкон",
            "polygon", new JSONArray("[[47.83,35.12],[47.84,35.12],[47.84,35.13],[47.83,35.13]]")
        );
    }

    private JSONObject outsidePreviousTerritory() {
        return Json.object(
            "city", "Запоріжжя", "kind", "apartment", "currency", "USD",
            "price", 13000, "rooms", 2, "title", "Квартира, балкон",
            "latitude", 47.8388, "longitude", 35.1396
        );
    }

    @Test
    public void mapCanShowAnOtherwiseMatchingObjectOutsideThePreviousTerritory() throws Exception {
        JSONObject filters = search();
        JSONObject record = outsidePreviousTerritory();
        assertFalse("Fixture must be excluded by the saved territory", Model.matches(record, filters, null));
        assertTrue("Editing the territory must show candidates outside its old boundary",
            Model.matches(record, MapFilters.candidates(filters), null));
    }

    @Test
    public void removingTerritoryKeepsCityTypeBudgetRoomsAndTextConstraints() throws Exception {
        JSONObject candidates = MapFilters.candidates(search());
        JSONObject record = outsidePreviousTerritory();
        assertTrue(Model.matches(record, candidates, null));
        for (Object[] change : new Object[][] {
            { "city", "Київ" }, { "kind", "house" }, { "price", 16000 },
            { "rooms", 1 }, { "title", "Квартира без лоджії" }
        }) {
            JSONObject excluded = Json.copy(record);
            Json.put(excluded, (String) change[0], change[1]);
            assertFalse("Map must preserve the " + change[0] + " constraint",
                Model.matches(excluded, candidates, null));
        }
    }

    @Test
    public void openingMapDoesNotClearOrMutateTheSavedTerritoryBeforeApply() throws Exception {
        JSONObject filters = search();
        String previous = filters.toString();
        JSONObject candidates = MapFilters.candidates(filters);
        assertFalse(candidates.has("polygon"));
        Json.put(candidates, "city", "Київ");
        assertEquals(previous, filters.toString());
        assertEquals(4, filters.getJSONArray("polygon").length());
    }

    @Test
    public void globalFavoriteMapKeepsFavoritesFromDifferentCities() {
        JSONObject candidates = MapFilters.candidates(Json.object("favorite", true, "currency", "USD"));
        JSONObject zaporizhzhia = outsidePreviousTerritory();
        Json.put(zaporizhzhia, "favorite", true);
        JSONObject lviv = Json.copy(zaporizhzhia);
        Json.put(lviv, "city", "Львів");
        Json.put(lviv, "latitude", 49.8397);
        Json.put(lviv, "longitude", 24.0297);
        assertTrue(Model.matches(zaporizhzhia, candidates, null));
        assertTrue(Model.matches(lviv, candidates, null));
        Json.put(lviv, "favorite", false);
        assertFalse(Model.matches(lviv, candidates, null));
    }
}

package ua.domtrack.mobile;

import static org.junit.Assert.*;
import java.util.*;
import org.json.*;
import org.junit.Test;

public class CurrentSearchTest {
    @Test public void ordinarySearchCreatesAnEnabledTargetWithTheRequestedFilters() {
        JSONObject saved = CurrentSearch.snapshot(List.of(), Json.object(
            "city", "Запоріжжя", "kind", "apartment", "priceMax", 15000, "currency", "USD"));
        assertEquals("domtrack-current-search", saved.optString("id"));
        assertTrue(saved.optString("name").contains("Текущий поиск"));
        assertTrue(saved.optBoolean("enabled"));
        assertEquals("Запоріжжя", saved.optJSONObject("filters").optString("city"));
        assertEquals("apartment", saved.optJSONObject("filters").optString("kind"));
        assertEquals(15000, saved.optJSONObject("filters").optInt("priceMax"));
        assertEquals("USD", saved.optJSONObject("filters").optString("currency"));
    }

    @Test public void subsequentSearchKeepsOneIdentityAndReplacesOnlyTheCurrentFilters() {
        JSONObject named = Json.object("id", "user-saved-1", "name", "My saved search", "enabled", false,
            "filters", Json.object("city", "Київ", "kind", "house", "priceMax", 42000));
        JSONObject previous = Json.object("id", "domtrack-current-search", "name", "Old current search",
            "enabled", true, "filters", Json.object("city", "Одеса", "kind", "house"));
        String namedBefore = named.toString(), previousBefore = previous.toString();
        List<JSONObject> saved = List.of(named, previous);
        JSONObject next = CurrentSearch.snapshot(saved, Json.object("city", "Львів", "kind", "both"));
        assertEquals("domtrack-current-search", next.optString("id"));
        assertEquals("Львів", next.optJSONObject("filters").optString("city"));
        assertEquals("both", next.optJSONObject("filters").optString("kind"));
        assertEquals(2, saved.size());
        assertEquals(namedBefore, named.toString());
        assertEquals(previousBefore, previous.toString());
        assertTrue(CurrentSearch.isCurrent(next));
        assertFalse(CurrentSearch.isCurrent(named));
    }

    @Test public void anExistingNamedSavedSearchDoesNotBlockTheNewCurrentObservation() {
        JSONObject named = Json.object("id", "named-paused-search", "name", "Named saved search", "enabled", false,
            "filters", Json.object("city", "Київ", "kind", "house", "priceMax", 42000));
        String before = named.toString();
        JSONObject next = CurrentSearch.snapshot(List.of(named), Json.object(
            "city", "Запоріжжя", "kind", "apartment", "priceMax", 15000));
        assertEquals("domtrack-current-search", next.optString("id"));
        assertTrue(next.optBoolean("enabled"));
        assertEquals("Запоріжжя", next.optJSONObject("filters").optString("city"));
        assertEquals(15000, next.optJSONObject("filters").optInt("priceMax"));
        assertEquals(before, named.toString());
    }

    @Test public void runningAnotherSearchDoesNotResumeAnExplicitlyPausedCurrentTarget() {
        JSONObject paused = Json.object("id", "domtrack-current-search", "enabled", false,
            "filters", Json.object("city", "Київ", "kind", "apartment"));
        JSONObject next = CurrentSearch.snapshot(List.of(paused), Json.object("city", "Одеса", "kind", "house"));
        assertFalse(next.optBoolean("enabled", true));
        assertEquals("Одеса", next.optJSONObject("filters").optString("city"));
        assertFalse(paused.optBoolean("enabled", true));
    }

    @Test public void nestedFiltersAreDetachedFromMutableUiAndPreviousSavedObjects() throws Exception {
        JSONObject filters = Json.object("city", "Київ", "kind", "both", "polygon",
            new JSONArray().put(new JSONArray().put(50.1).put(30.1)), "extra", Json.object("rooms", 2));
        JSONObject previous = Json.object("id", "domtrack-current-search", "enabled", false,
            "metadata", Json.object("note", "keep"));
        JSONObject next = CurrentSearch.snapshot(List.of(previous), filters);
        assertNotNull(next.optJSONObject("filters"));
        assertNotNull(next.optJSONObject("metadata"));
        Json.put(filters.optJSONObject("extra"), "rooms", 9);
        filters.optJSONArray("polygon").optJSONArray(0).put(0, 99.0);
        Json.put(previous.optJSONObject("metadata"), "note", "changed");
        assertEquals(2, next.optJSONObject("filters").optJSONObject("extra").optInt("rooms"));
        assertEquals(50.1, next.optJSONObject("filters").optJSONArray("polygon").optJSONArray(0).optDouble(0), 0.0);
        assertEquals("keep", next.optJSONObject("metadata").optString("note"));
        Json.put(next.optJSONObject("filters"), "city", "Львів");
        assertEquals("Київ", filters.optString("city"));
    }

    @Test public void knownCityAliasesAndMissingKindProduceUsableSourceFilters() {
        JSONObject next = CurrentSearch.snapshot(List.of(), Json.object("city", "Запорожье"));
        assertNotNull(next.optJSONObject("filters"));
        assertEquals("Запорожье", next.optJSONObject("filters").optString("city"));
        assertEquals("both", next.optJSONObject("filters").optString("kind"));
        assertFalse(Sources.sections(next.optJSONObject("filters").optString("city"),
            next.optJSONObject("filters").optString("kind")).isEmpty());
        for (String kind : List.of("apartment", "house", "both")) {
            JSONObject saved = CurrentSearch.snapshot(List.of(), Json.object("city", "Київ", "kind", kind));
            assertEquals(kind, saved.optJSONObject("filters").optString("kind"));
        }
    }

    @Test public void malformedCityOrKindCannotReplaceTheCurrentObservation() {
        JSONObject current = Json.object("id", "domtrack-current-search", "enabled", false,
            "filters", Json.object("city", "Київ", "kind", "both"));
        String before = current.toString();
        assertThrows(IllegalArgumentException.class, () -> CurrentSearch.snapshot(List.of(current), null));
        for (JSONObject invalid : List.of(new JSONObject(), Json.object("city", ""),
            Json.object("city", "Unknown"), Json.object("city", "Київ", "kind", "castle"),
            Json.object("city", "Київ", "kind", ""), Json.object("city", "Київ", "kind", JSONObject.NULL))) {
            assertThrows(invalid.toString(), IllegalArgumentException.class,
                () -> CurrentSearch.snapshot(List.of(current), invalid));
        }
        assertEquals(before, current.toString());
    }
}

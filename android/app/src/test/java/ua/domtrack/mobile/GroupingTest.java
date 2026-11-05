package ua.domtrack.mobile;

import static org.junit.Assert.*;

import java.util.*;
import org.json.*;
import org.junit.Test;

public class GroupingTest {

    private JSONObject row(String id, double price) {
        return Json.object(
            "id",
            id,
            "url",
            "https://example.org/" + id,
            "title",
            "Квартира с балконом у парка",
            "description",
            "Две комнаты, кирпичный дом, отдельная кухня и большой балкон.",
            "city",
            "Запоріжжя",
            "kind",
            "apartment",
            "price",
            price,
            "currency",
            "USD"
        );
    }

    private JSONObject pair(JSONObject a, JSONObject b) {
        return Json.object(
            "a",
            a.optString("id"),
            "b",
            b.optString("id"),
            "hashA",
            SemanticIndex.key(a),
            "hashB",
            SemanticIndex.key(b)
        );
    }

    @Test
    public void probableGroupKeepsAllOriginalsAndChoosesCheapest() {
        JSONObject a = row("a", 12000),
            b = row("b", 11000);
        List<JSONObject> shown = SemanticIndex.display(
            List.of(a, b),
            null,
            "USD",
            Json.object("pairs", new JSONArray().put(pair(a, b)))
        );
        assertEquals(1, shown.size());
        assertEquals("b", shown.get(0).optString("id"));
        assertEquals(2, shown.get(0).optJSONArray("probableAlternatives").length());
        assertEquals(12000, a.optDouble("price"), 0);
        assertFalse(a.has("sameGroup"));
        assertFalse(b.has("sameGroup"));
    }

    @Test
    public void similarityChainCannotCollapseUnrelatedEndpoints() {
        JSONObject a = row("a", 12000),
            b = row("b", 11000),
            c = row("c", 10000);
        List<JSONObject> shown = SemanticIndex.display(
            List.of(a, b, c),
            null,
            "USD",
            Json.object("pairs", new JSONArray().put(pair(a, b)).put(pair(b, c)))
        );
        assertEquals(2, shown.size());
        assertTrue(
            shown
                .stream()
                .noneMatch(
                    r ->
                        r.optJSONArray("probableAlternatives") != null &&
                        r.optJSONArray("probableAlternatives").length() == 3
                )
        );
    }

    @Test
    public void ChangedDescriptionInvalidatesPreviousAiMatch() {
        JSONObject a = row("a", 12000),
            b = row("b", 11000),
            index = Json.object("pairs", new JSONArray().put(pair(a, b)));
        Json.put(b, "description", "Совсем другой объект; прежний текст был заменён.");
        assertEquals(2, SemanticIndex.display(List.of(a, b), null, "USD", index).size());
    }

    @Test
    public void NeighborPropertyCoordinatesDoNotBecomeMainPropertyLocation() {
        String url = "https://example.org/a";
        String html =
            "<head><link rel=canonical href='" +
            url +
            "'></head><script type='application/ld+json'>" +
            Json.object(
                "@type",
                "Apartment",
                "url",
                "https://example.org/b",
                "geo",
                Json.object("latitude", 47.8, "longitude", 35.1)
            ) +
            "</script>";
        assertFalse(Coordinates.parse(html, url).has("latitude"));
    }
}

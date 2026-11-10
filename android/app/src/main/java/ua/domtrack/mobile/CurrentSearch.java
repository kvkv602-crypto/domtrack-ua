package ua.domtrack.mobile;

import java.util.*;
import org.json.*;

/** The latest explicit search reuses the saved-search observation engine. */
final class CurrentSearch {
    static final String ID = "domtrack-current-search";

    static boolean isCurrent(JSONObject search) {
        return search != null && ID.equals(search.optString("id"));
    }

    static JSONObject snapshot(List<JSONObject> saved, JSONObject filters) {
        if (filters == null) throw new IllegalArgumentException("Выберите параметры поиска");
        String city = filters.optString("city");
        if (Arrays.stream(Sources.CITIES).noneMatch(row ->
                Bilingual.city(row[0]).equals(Bilingual.city(city))))
            throw new IllegalArgumentException("Выберите город из списка");
        String kind = filters.optString("kind", "both");
        if ((filters.has("kind") && filters.isNull("kind")) ||
                !List.of("apartment", "house", "both").contains(kind))
            throw new IllegalArgumentException("Выберите тип недвижимости из списка");
        JSONObject current = saved.stream().filter(CurrentSearch::isCurrent)
            .findFirst().map(Json::copy).orElseGet(JSONObject::new);
        JSONObject snapshot = Json.copy(filters);
        Json.put(snapshot, "kind", kind);
        Json.put(current, "id", ID);
        Json.put(current, "name", "Текущий поиск · " + city);
        Json.put(current, "filters", snapshot);
        if (!current.has("enabled")) Json.put(current, "enabled", true);
        return current;
    }
}

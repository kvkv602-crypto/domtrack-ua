package ua.domtrack.mobile;

import org.json.JSONObject;

/** Search constraints for the candidates shown while a map territory is being edited. */
final class MapFilters {

    static JSONObject candidates(JSONObject filters) {
        JSONObject candidates = Json.copy(filters);
        candidates.remove("polygon");
        return candidates;
    }
}

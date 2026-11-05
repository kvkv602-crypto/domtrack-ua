package ua.domtrack.mobile;

import java.util.*;
import org.json.*;

final class Json {

    static JSONObject obj(String text) {
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Некорректные JSON-данные", e);
        }
    }

    static JSONObject object(Object... pairs) {
        JSONObject o = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) put(o, String.valueOf(pairs[i]), pairs[i + 1]);
        return o;
    }

    static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value == null ? JSONObject.NULL : value);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static JSONObject copy(JSONObject o) {
        return obj(o.toString());
    }

    static List<JSONObject> objects(JSONArray a) {
        List<JSONObject> r = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) r.add(o);
        }
        return r;
    }

    static JSONArray array(Collection<JSONObject> list) {
        JSONArray a = new JSONArray();
        for (JSONObject o : list) a.put(o);
        return a;
    }

    static List<String> keys(JSONObject object) {
        List<String> result = new ArrayList<>();
        if (object != null) object.keys().forEachRemaining(result::add);
        return result;
    }
}

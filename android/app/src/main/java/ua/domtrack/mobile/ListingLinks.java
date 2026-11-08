package ua.domtrack.mobile;

import java.net.URI;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Public listing identities only; query parameters never identify an OLX advert. */
final class ListingLinks {
    private static final Set<String> OLX_HOSTS = Set.of("olx.ua", "www.olx.ua", "m.olx.ua");
    private static final Pattern OLX_PATH = Pattern.compile(
        "/(?:d/)?(?:uk/)?obyavlenie/[^/%\\\\?#]+-ID([A-Za-z0-9]+)\\.html"
    );

    static final class Olx {
        final String url, path, id;
        Olx(String u, String p, String i) { url = u; path = p; id = i; }
    }

    static boolean olxHost(String value) {
        try {
            String host = URI.create(value).getHost();
            return host != null && OLX_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (Exception e) { return false; }
    }

    static Olx olx(String value) {
        try {
            URI u = URI.create(value);
            if (value.length() > 2048 || value.matches("(?s).*[\\x00-\\x20\\\\].*") ||
                !"https".equals(u.getScheme()) || !olxHost(value) || u.getUserInfo() != null ||
                u.getPort() != -1 || u.getRawFragment() != null) return null;
            Matcher m = OLX_PATH.matcher(u.getRawPath());
            if (!m.matches()) return null;
            String clean = "https://" + u.getHost().toLowerCase(Locale.ROOT) + u.getRawPath();
            return new Olx(clean, u.getRawPath(), m.group(1));
        } catch (Exception e) { return null; }
    }

    static boolean sameOlx(String a, String b) {
        Olx x = olx(a), y = olx(b);
        return x != null && y != null && x.path.equals(y.path) && x.id.equals(y.id);
    }

    static boolean olxWatch(JSONObject row) {
        String source = row.optString("source");
        return source.equals("olx") || (!Set.of("flatfy", "lun").contains(source) && olx(row.optString("url")) != null);
    }

    /** A watch must observe its target; another catalog card cannot count as a successful check. */
    static JSONObject watched(List<JSONObject> rows, JSONObject previous) {
        JSONObject selected = null;
        for (JSONObject row : rows) {
            boolean same = olxWatch(previous)
                ? row.optString("source").equals("olx") && sameOlx(previous.optString("url"), row.optString("url"))
                : Model.identity(previous).equals(Model.identity(row));
            if (!same) continue;
            if (selected != null && (selected.optDouble("price") != row.optDouble("price") ||
                !selected.optString("currency").equals(row.optString("currency")) ||
                !Model.material(selected).equals(Model.material(row))))
                throw new IllegalArgumentException("Наблюдение: противоречащие данные исходного объявления");
            selected = row;
        }
        if (selected == null) throw new IllegalArgumentException(
            "Исходное объявление не подтверждено; прежняя цена не считается новой проверкой"
        );
        JSONObject result = Json.copy(selected);
        // Existing rows are keyed by Model.identity(url). Keep that key, including old sharing queries.
        Json.put(result, "url", previous.optString("url"));
        Model.validate(result);
        return result;
    }
}

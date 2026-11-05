package ua.domtrack.mobile;

import java.util.*;
import java.util.regex.*;
import org.json.*;

final class Dedupe {

    static final Set<String> STOP = new HashSet<>(
        Arrays.asList(
            "квартира квартиры квартир квартиру кімнатна комнатная комнатный кімнатний дом будинок продажа продаж продам продается продається купити купить від от собственника владельца власника просторная простора светлая світла новая нова ремонт ремонтом загальна общая площадь площа поверх этаж возле рядом поруч квартира комнат комнаты кімнат центр районе район город місто".split(
                " "
            )
        )
    );

    static Set<String> words(String text) {
        Set<String> words = new HashSet<>();
        for (String w : Model.normalized(text).replaceAll("[^\\p{L}\\p{N}]+", " ").split(" +"))
            if (w.length() >= 4 && !STOP.contains(w)) words.add(concept(w));
        return words;
    }

    static String concept(String word) {
        return switch (word) {
            case "тераса", "терасою", "террасой", "террасою" -> "терраса";
            case "бібліотекою", "бібліотека", "библиотекой" -> "библиотека";
            case "даху", "крыше" -> "крыша";
            case "авторський", "авторским" -> "авторский";
            case "камином", "каміном", "камін" -> "камин";
            default -> word;
        };
    }

    static String address(JSONObject r) {
        String s = Model.normalized(r.optString("address")).replaceAll(
            "(?iu)(?:^|[ ,])(?:вул(?:иця)?|ул(?:ица)?|street)\\.? *",
            " "
        );
        Matcher m = Pattern.compile(
            "^(.*?)[, ]+(\\d+[а-яa-zіїєґ]?(?:[/\\-]\\d+)?)(?:[, ]+(?:кв\\.?|квартира) *([0-9]+))?$"
        ).matcher(s);
        if (!m.matches()) return "";
        List<String> tokens = new ArrayList<>(
            Arrays.asList(m.group(1).replaceAll("[^\\p{L}\\p{N}]+", " ").trim().split(" +"))
        );
        Collections.sort(tokens);
        return String.join(" ", tokens) + "#" + m.group(2) + "#" + Objects.toString(m.group(3), "");
    }

    static List<String> evidence(JSONObject a, JSONObject b, JSONObject quote) {
        List<String> out = new ArrayList<>();
        if (
            !Model.normalized(a.optString("city")).equals(Model.normalized(b.optString("city"))) ||
            !a.optString("kind").equals(b.optString("kind"))
        ) return out;
        String address = address(a);
        if (address.isBlank() || !address.equals(address(b))) return out;
        for (String key : List.of("rooms", "floor", "floorsTotal"))
            if (a.has(key) && b.has(key) && a.optInt(key) != b.optInt(key)) return out;
        if (
            !a.has("rooms") ||
            !b.has("rooms") ||
            (a.optString("kind").equals("apartment") && (!a.has("floor") || !b.has("floor")))
        ) return out;
        double aa = a.optDouble("area", 0),
            ba = b.optDouble("area", 0);
        if (
            aa <= 0 || ba <= 0 || Math.abs(aa - ba) > Math.max(.5, Math.min(aa, ba) * .005)
        ) return out;
        Set<String> aw = words(a.optString("title")),
            bw = words(b.optString("title"));
        aw.removeAll(words(a.optString("city") + " " + a.optString("address")));
        bw.removeAll(words(b.optString("city") + " " + b.optString("address")));
        long common = aw.stream().filter(bw::contains).count();
        boolean title = common >= 4 && (double) common / (aw.size() + bw.size() - common) >= .7;
        String ap = a.optString("contactPhone").replaceAll("\\D", ""),
            bp = b.optString("contactPhone").replaceAll("\\D", "");
        if (ap.startsWith("0") && ap.length() == 10) ap = "38" + ap;
        if (bp.startsWith("0") && bp.length() == 10) bp = "38" + bp;
        boolean phone = ap.matches("380[0-9]{9}") && ap.equals(bp);
        if (!title && !phone) return out;
        out.add("Совпадают адрес, комнаты, площадь и известные этажи");
        if (title) out.add("Близкие содержательные слова независимо от порядка");
        if (phone) out.add("Совпадает открытый телефон");
        double x = Model.convert(a.optDouble("price"), a.optString("currency"), "UAH", quote),
            y = Model.convert(b.optDouble("price"), b.optString("currency"), "UAH", quote);
        if (Double.isFinite(x) && Double.isFinite(y) && Math.min(x, y) > 0) out.add(
            String.format(
                Locale.ROOT,
                "Разница цен по одному курсу НБУ: %.1f%%",
                (Math.abs(x - y) / Math.min(x, y)) * 100
            )
        );
        else if (a.optString("currency").equals(b.optString("currency"))) out.add(
            String.format(
                Locale.ROOT,
                "Разница исходных цен: %.1f%%",
                (Math.abs(a.optDouble("price") - b.optDouble("price")) /
                        Math.min(a.optDouble("price"), b.optDouble("price"))) *
                    100
            )
        );
        return out;
    }

    static List<JSONObject> collapsed(List<JSONObject> rows, JSONObject quote, String currency) {
        List<JSONObject> out = new ArrayList<>();
        Map<String, List<JSONObject>> groups = new LinkedHashMap<>();
        for (JSONObject r : rows) {
            String group = r.optString("sameGroup");
            if (group.isBlank()) out.add(r);
            else groups.computeIfAbsent(group, k -> new ArrayList<>()).add(r);
        }
        for (List<JSONObject> group : groups.values()) {
            boolean comparable = group
                .stream()
                .allMatch(r ->
                    Double.isFinite(
                        Model.convert(
                            r.optDouble("price"),
                            r.optString("currency"),
                            currency,
                            quote
                        )
                    )
                );
            boolean oneCurrency =
                group
                    .stream()
                    .map(r -> r.optString("currency"))
                    .distinct()
                    .count() ==
                1;
            if (!comparable && !oneCurrency) {
                out.addAll(group);
                continue;
            }
            JSONObject chosen = group
                .stream()
                .min(
                    Comparator.comparingDouble(r ->
                        comparable
                            ? Model.convert(
                                r.optDouble("price"),
                                r.optString("currency"),
                                currency,
                                quote
                            )
                            : r.optDouble("price")
                    )
                )
                .orElseThrow();
            JSONObject display = Json.copy(chosen);
            Json.put(display, "confirmedAlternatives", Json.array(group));
            out.add(display);
        }
        return out;
    }
}

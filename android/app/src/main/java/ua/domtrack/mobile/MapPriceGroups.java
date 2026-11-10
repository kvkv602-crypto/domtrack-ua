package ua.domtrack.mobile;

import java.util.*;
import org.json.JSONObject;

/** Screen-space map chips, independent of Android rendering. */
final class MapPriceGroups {
    interface TextWidth { float measure(String text); }

    /** Reused between camera frames; replaced when records, requested currency or FX quote refresh. */
    static final class Pricing {
        final String currency;
        private final JSONObject quote;
        private final Map<JSONObject, Price> prices = new IdentityHashMap<>();
        private final java.text.DecimalFormat whole = new java.text.DecimalFormat("#,##0",
            java.text.DecimalFormatSymbols.getInstance(Locale.forLanguageTag("ru")));
        private final java.text.DecimalFormat abbreviated = new java.text.DecimalFormat("0.##",
            java.text.DecimalFormatSymbols.getInstance(Locale.forLanguageTag("ru")));
        private String date = Model.date();

        Pricing(String currency, JSONObject quote) { this.currency = currency; this.quote = quote; }

        void refreshDate() {
            String today = Model.date();
            if (!today.equals(date)) { date = today; prices.clear(); }
        }

        double converted(JSONObject record) { return price(record).converted; }

        private Price price(JSONObject record) {
            Price price = prices.get(record);
            double original = record.optDouble("price");
            String from = record.optString("currency");
            if (price == null || price.original != original || !price.from.equals(from)) {
                double value = Model.convert(original, from, currency, quote);
                String label = compact(Double.isFinite(value) ? value : original) + " " +
                    (Double.isFinite(value) ? currency : from);
                price = new Price(original, from, value, label);
                prices.put(record, price);
            }
            return price;
        }

        private String compact(double value) {
            double divisor = value >= 1_000_000 ? 1_000_000 : value >= 1_000 ? 1_000 : 1;
            return (divisor == 1 ? whole : abbreviated).format(value / divisor) +
                (divisor == 1_000_000 ? " млн" : divisor == 1_000 ? " тыс." : "");
        }
    }

    private static final class Price {
        final double original, converted;
        final String from, label;
        Price(double original, String from, double converted, String label) {
            this.original = original; this.from = from; this.converted = converted; this.label = label;
        }
    }

    static final class Entry {
        final JSONObject record;
        final float x, y;
        Entry(JSONObject record, float x, float y) { this.record = record; this.x = x; this.y = y; }
    }

    static final class Group {
        final List<Entry> members;
        final String label;
        final float anchorX, anchorY, left, top, right, bottom;
        Group(List<Entry> members, String label, float x, float y, float left, float top, float right, float bottom) {
            this.members = members; this.label = label; anchorX = x; anchorY = y;
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
    }

    static List<Group> layout(List<Entry> entries, float width, float height, float density,
        String currency, JSONObject quote, TextWidth textWidth) {
        return layout(entries, width, height, density, new Pricing(currency, quote), textWidth);
    }

    static List<Group> layout(List<Entry> entries, float width, float height, float density,
        Pricing pricing, TextWidth textWidth) {
        if (width <= 0 || height <= 0) return List.of();
        pricing.refreshDate();
        ArrayList<Entry> visible = new ArrayList<>();
        for (Entry entry : entries) if (Float.isFinite(entry.x) && Float.isFinite(entry.y) &&
            entry.x >= 0 && entry.x <= width && entry.y >= 0 && entry.y <= height) visible.add(entry);
        visible.sort(Comparator.comparing((Entry entry) -> entry.record.optString("id"))
            .thenComparingDouble(entry -> entry.x).thenComparingDouble(entry -> entry.y));
        ArrayList<Group> groups = new ArrayList<>();
        for (Entry entry : visible) groups.add(chip(List.of(entry), width, height, density, pricing, textWidth));
        // A merged label can grow into a neighbour. Recompute until all touch areas are separate.
        boolean changed;
        do {
            changed = false;
            int[] parent = new int[groups.size()];
            for (int i = 0; i < parent.length; i++) parent[i] = i;
            float gap = 6 * density;
            float minimumTop = Float.POSITIVE_INFINITY, maximumTop = Float.NEGATIVE_INFINITY;
            for (Group group : groups) {
                minimumTop = Math.min(minimumTop, group.top); maximumTop = Math.max(maximumTop, group.top);
            }
            // Skip distant rows during pans. A tight cluster is faster in its existing stable order.
            boolean sweep = maximumTop - minimumTop > (48 * density + gap) * 2;
            Integer[] byTop = sweep ? new Integer[groups.size()] : null;
            if (sweep) {
                for (int i = 0; i < byTop.length; i++) byTop[i] = i;
                Arrays.sort(byTop, Comparator.comparingDouble(index -> groups.get(index).top));
            }
            for (int position = 0; position < groups.size(); position++) {
                int i = sweep ? byTop[position] : position;
                Group a = groups.get(i);
                for (int next = position + 1; next < groups.size(); next++) {
                    int j = sweep ? byTop[next] : next;
                    Group b = groups.get(j);
                    if (sweep && b.top >= a.bottom + gap) break;
                    if (!sweep && (a.top >= b.bottom + gap || a.bottom + gap <= b.top)) continue;
                    if (a.left < b.right + gap && a.right + gap > b.left) {
                        int first = root(parent, i), second = root(parent, j);
                        if (first != second) { parent[second] = first; changed = true; }
                    }
                }
            }
            if (changed) {
                Map<Integer, ArrayList<Entry>> merged = new LinkedHashMap<>();
                for (int i = 0; i < groups.size(); i++)
                    merged.computeIfAbsent(root(parent, i), unused -> new ArrayList<>()).addAll(groups.get(i).members);
                groups.clear();
                for (ArrayList<Entry> members : merged.values()) {
                    members.sort(Comparator.comparing((Entry entry) -> entry.record.optString("id"))
                        .thenComparingDouble(entry -> entry.x).thenComparingDouble(entry -> entry.y));
                    groups.add(chip(members, width, height, density, pricing, textWidth));
                }
            }
        } while (changed);
        return groups;
    }

    private static int root(int[] parent, int index) {
        while (parent[index] != index) { parent[index] = parent[parent[index]]; index = parent[index]; }
        return index;
    }

    private static Group chip(List<Entry> members, float viewportWidth, float viewportHeight, float density,
        Pricing pricing, TextWidth textWidth) {
        float x = 0, y = 0;
        for (Entry entry : members) { x += entry.x / members.size(); y += entry.y / members.size(); }
        String label = label(members, pricing);
        float margin = Math.min(4 * density, Math.min(viewportWidth, viewportHeight) / 4);
        float width = Math.min(viewportWidth - 2 * margin, Math.max(48 * density, textWidth.measure(label) + 24 * density));
        float height = Math.min(viewportHeight - 2 * margin, 48 * density);
        float left = Math.max(margin, Math.min(viewportWidth - margin - width, x - width / 2));
        float top = Math.max(margin, Math.min(viewportHeight - margin - height, y - height - 2 * density));
        return new Group(List.copyOf(members), label, x, y, left, top, left + width, top + height);
    }

    private static String label(List<Entry> members, Pricing pricing) {
        if (members.size() == 1) return pricing.price(members.get(0).record).label;
        double minimum = Double.POSITIVE_INFINITY;
        for (Entry entry : members) {
            double value = pricing.price(entry.record).converted;
            if (!Double.isFinite(value) || value <= 0) return count(members.size());
            minimum = Math.min(minimum, value);
        }
        return members.size() + " · от " + pricing.compact(minimum) + " " + pricing.currency;
    }

    private static String count(int size) {
        int last = size % 10, lastTwo = size % 100;
        return size + (last == 1 && lastTwo != 11 ? " объект" :
            last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? " объекта" : " объектов");
    }

}

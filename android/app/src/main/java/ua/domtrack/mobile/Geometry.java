package ua.domtrack.mobile;

import org.json.*;

final class Geometry {

    private static double cross(JSONArray a, JSONArray b, JSONArray c) {
        return (
            (b.optDouble(0) - a.optDouble(0)) * (c.optDouble(1) - a.optDouble(1)) -
            (b.optDouble(1) - a.optDouble(1)) * (c.optDouble(0) - a.optDouble(0))
        );
    }

    static boolean valid(JSONArray p) {
        if (p == null || p.length() < 3 || p.length() > 500) return false;
        double area = 0;
        for (int i = 0; i < p.length(); i++) {
            JSONArray a = p.optJSONArray(i),
                b = p.optJSONArray((i + 1) % p.length());
            if (
                a == null ||
                b == null ||
                Math.abs(a.optDouble(0, Double.NaN)) > 90 ||
                Math.abs(a.optDouble(1, Double.NaN)) > 180 ||
                !Double.isFinite(a.optDouble(0)) ||
                !Double.isFinite(a.optDouble(1))
            ) return false;
            area += a.optDouble(0) * b.optDouble(1) - a.optDouble(1) * b.optDouble(0);
            for (int j = i + 2; j < p.length(); j++) {
                if ((j + 1) % p.length() == i) continue;
                JSONArray c = p.optJSONArray(j),
                    d = p.optJSONArray((j + 1) % p.length());
                if (c == null || d == null) return false;
                if (
                    cross(a, b, c) * cross(a, b, d) < 0 && cross(c, d, a) * cross(c, d, b) < 0
                ) return false;
            }
        }
        return Math.abs(area) > 1e-10;
    }

    static JSONArray simplify(JSONArray p, double tolerance) {
        JSONArray out = new JSONArray();
        if (p.length() == 0) return out;
        JSONArray last = p.optJSONArray(0);
        out.put(last);
        for (int i = 1; i < p.length() - 1; i++) {
            JSONArray q = p.optJSONArray(i);
            if (
                Math.hypot(
                    q.optDouble(0) - last.optDouble(0),
                    q.optDouble(1) - last.optDouble(1)
                ) >=
                tolerance
            ) {
                out.put(q);
                last = q;
            }
        }
        if (p.length() > 1) out.put(p.optJSONArray(p.length() - 1));
        return out;
    }
}

package ua.domtrack.mobile;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.*;
import org.json.*;

final class PriceChart extends View {

    private final java.util.List<JSONObject> rows;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    PriceChart(Context c, java.util.List<JSONObject> history) {
        super(c);
        rows = new ArrayList<>(history);
        Collections.reverse(rows);
        setContentDescription("График наблюдавшихся цен в исходной валюте");
    }

    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (rows.isEmpty()) return;
        String currency = rows.get(rows.size() - 1).optString("currency");
        java.util.List<JSONObject> points = rows
            .stream()
            .filter(r -> r.optString("currency").equals(currency))
            .toList();
        double min = points
                .stream()
                .mapToDouble(r -> r.optDouble("price"))
                .min()
                .orElse(0),
            max = points
                .stream()
                .mapToDouble(r -> r.optDouble("price"))
                .max()
                .orElse(1);
        if (max == min) {
            min *= .99;
            max *= 1.01;
        }
        float left = 24,
            top = 38,
            w = getWidth() - 48,
            h = getHeight() - 76;
        paint.setColor(Color.rgb(19, 125, 102));
        paint.setStrokeWidth(5);
        for (int i = 0; i < points.size(); i++) {
            float x = left + (w * i) / Math.max(1, points.size() - 1),
                y = top + h * (float) (1 - (points.get(i).optDouble("price") - min) / (max - min));
            c.drawCircle(x, y, 5, paint);
            if (i > 0) {
                float px = left + (w * (i - 1)) / Math.max(1, points.size() - 1),
                    py =
                        top +
                        h *
                        (float) (1 - (points.get(i - 1).optDouble("price") - min) / (max - min));
                c.drawLine(px, py, x, y, paint);
            }
        }
        paint.setTextSize(28);
        paint.setColor(Color.DKGRAY);
        c.drawText(
            String.format(Locale.ROOT, "%.0f – %.0f %s", min, max, currency),
            left,
            28,
            paint
        );
    }
}

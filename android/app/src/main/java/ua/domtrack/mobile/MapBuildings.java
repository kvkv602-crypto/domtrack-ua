package ua.domtrack.mobile;

import static org.maplibre.android.style.expressions.Expression.*;
import static org.maplibre.android.style.layers.PropertyFactory.*;

import org.json.*;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.*;
import org.maplibre.android.style.sources.VectorSource;

/** Approximate OpenMapTiles building volumes; never derived from a listing's floor count. */
final class MapBuildings {
    static final String LAYER_ID = "domtrack-3d-buildings";

    /** Call with a fully loaded style. Unsupported styles safely remain flat. Idempotent. */
    static void apply(Style style) {
        if (style == null || style.getLayer(LAYER_ID) != null) return;
        JSONObject document = Json.obj(style.getJson());
        JSONArray layers = document.optJSONArray("layers");
        JSONObject sources = document.optJSONObject("sources");
        if (layers == null || sources == null) return;
        String sourceId = "";
        for (int i = 0; i < layers.length(); i++) {
            JSONObject layer = layers.optJSONObject(i);
            if (layer == null || !"building".equals(layer.optString("source-layer"))) continue;
            String candidate = layer.optString("source");
            JSONObject source = sources.optJSONObject(candidate);
            if (source != null && "vector".equals(source.optString("type")) &&
                style.getSource(candidate) instanceof VectorSource) {
                sourceId = candidate;
                break;
            }
        }
        if (sourceId.isEmpty()) return;
        // Liberty already extrudes buildings. Keep a single managed layer across all styles.
        for (int i = 0; i < layers.length(); i++) {
            JSONObject layer = layers.optJSONObject(i);
            if (layer != null && sourceId.equals(layer.optString("source")) &&
                "building".equals(layer.optString("source-layer")) &&
                "fill-extrusion".equals(layer.optString("type"))) {
                Layer existing = style.getLayer(layer.optString("id"));
                if (existing != null) existing.setProperties(visibility(Property.NONE));
            }
        }
        FillExtrusionLayer buildings = new FillExtrusionLayer(LAYER_ID, sourceId);
        buildings.setSourceLayer("building");
        buildings.setMinZoom(14.5f);
        buildings.setFilter(all(has("render_height"), has("render_min_height"),
            neq(get("hide_3d"), literal(true)), neq(get("hide_3d"), literal(1)),
            neq(get("hide_3d"), literal("true")),
            gt(get("render_height"), literal(0)), lte(get("render_height"), literal(1000)),
            gte(get("render_min_height"), literal(0)),
            lte(get("render_min_height"), get("render_height"))));
        buildings.setProperties(fillExtrusionColor(0xffc5d2e0), fillExtrusionOpacity(.88f),
            fillExtrusionHeight(get("render_height")), fillExtrusionBase(get("render_min_height")));
        for (Layer layer : style.getLayers()) if (layer instanceof SymbolLayer) {
            style.addLayerBelow(buildings, layer.getId());
            return;
        }
        style.addLayer(buildings);
    }

    /** Only changes the managed layer; the base map, labels and camera remain untouched. */
    static void setVisible(Style style, boolean visible) {
        if (style == null) return;
        Layer layer = style.getLayer(LAYER_ID);
        if (layer != null) layer.setProperties(visibility(visible ? Property.VISIBLE : Property.NONE));
    }
}

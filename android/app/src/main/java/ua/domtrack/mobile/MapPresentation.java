package ua.domtrack.mobile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Location facts used by the map; a source coordinate is not proof of an exact house. */
final class MapPresentation {

    enum Precision { MISSING, APPROXIMATE, UNSPECIFIED }

    static boolean hasCoordinates(JSONObject record) {
        if (record == null) return false;
        double latitude = record.optDouble("latitude"), longitude = record.optDouble("longitude");
        return Double.isFinite(latitude) && Double.isFinite(longitude) &&
            Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180 &&
            (latitude != 0 || longitude != 0);
    }

    static Precision precision(JSONObject record) {
        if (!hasCoordinates(record)) return Precision.MISSING;
        return switch (record.optString("locationAccuracy")) {
            case "approximate", "district", "city", "area" -> Precision.APPROXIMATE;
            default -> Precision.UNSPECIFIED;
        };
    }

    static String routeUrl(JSONObject record) {
        if (record == null) return "";
        String address = record.optString("address").trim();
        String destination;
        if (!address.isEmpty()) destination = URLEncoder.encode(
            (record.optString("city").trim() + " " + address).trim(), StandardCharsets.UTF_8);
        else if (hasCoordinates(record)) destination = record.optDouble("latitude") + "," + record.optDouble("longitude");
        else return "";
        return "https://www.google.com/maps/dir/?api=1&destination=" + destination;
    }
}

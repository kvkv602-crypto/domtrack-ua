package ua.domtrack.mobile;

import static org.junit.Assert.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.json.*;
import org.junit.Test;

public class MapPresentationTest {

    @Test
    public void invalidOrAbsentCoordinatesCannotCreateAPropertyPin() {
        assertFalse(MapPresentation.hasCoordinates(null));
        assertFalse(MapPresentation.hasCoordinates(new JSONObject()));
        for (double[] point : new double[][] { { 91, 35 }, { 47, 181 }, { -91, 35 }, { 0, 0 } }) {
            assertFalse(MapPresentation.hasCoordinates(Json.object("latitude", point[0], "longitude", point[1])));
        }
        assertTrue(MapPresentation.hasCoordinates(Json.object("latitude", 47.8388, "longitude", 35.1396)));
    }

    @Test
    public void sourceOrUnrecognizedAccuracyNeverBecomesVerifiedHouseAccuracy() {
        JSONObject record = Json.object("latitude", 47.8388, "longitude", 35.1396);
        for (String accuracy : new String[] { "source", "source-unspecified", "", "exact", "verified" }) {
            Json.put(record, "locationAccuracy", accuracy);
            assertEquals(MapPresentation.Precision.UNSPECIFIED, MapPresentation.precision(record));
        }
        Json.put(record, "locationAccuracy", "approximate");
        assertEquals(MapPresentation.Precision.APPROXIMATE, MapPresentation.precision(record));
        record.remove("latitude");
        assertEquals(MapPresentation.Precision.MISSING, MapPresentation.precision(record));
    }

    @Test
    public void addressRouteWorksWithoutCoordinatesAndDoesNotUseAnUnverifiedPoint() {
        JSONObject record = Json.object("city", "Запоріжжя", "address", "вул. Брюллова, 16");
        String route = URLDecoder.decode(MapPresentation.routeUrl(record), StandardCharsets.UTF_8);
        assertTrue(route.startsWith("https://www.google.com/maps/dir/?api=1&destination="));
        assertTrue(route.contains("Запоріжжя вул. Брюллова, 16"));
        Json.put(record, "latitude", 47.8388);
        Json.put(record, "longitude", 35.1396);
        assertEquals(route, URLDecoder.decode(MapPresentation.routeUrl(record), StandardCharsets.UTF_8));
    }

    @Test
    public void coordinateRouteNeedsARealPointWhenNoAddressIsAvailable() {
        assertEquals("", MapPresentation.routeUrl(Json.object("city", "Запоріжжя")));
        JSONObject record = Json.object("latitude", 47.8388, "longitude", 35.1396);
        assertTrue(MapPresentation.routeUrl(record).contains("destination=47.8388,35.1396"));
        Json.put(record, "latitude", 91);
        assertEquals("", MapPresentation.routeUrl(record));
    }
}

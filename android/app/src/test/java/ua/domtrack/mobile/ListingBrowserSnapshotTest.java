package ua.domtrack.mobile;

import static org.junit.Assert.*;

import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class ListingBrowserSnapshotTest {
    static final String URL = "https://www.olx.ua/d/uk/obyavlenie/training-apartment-IDabc123.html";
    static final String HTML = "<html><head><link rel='canonical' href='" + URL + "'></head><body><h1>Продам квартиру</h1></body></html>";

    private String callback(String html, String url) throws Exception {
        return JSONObject.quote(new JSONObject().put("html", html).put("url", url).toString());
    }

    @Test public void returnsActualCanonicalRatherThanRequestedHostOrSharingQuery() throws Exception {
        String actual = URL.replace("www.", "m.");
        ListingBrowserSnapshot.Result result = ListingBrowserSnapshot.decode(
            callback(HTML.replace(URL, actual), URL), URL + "?utm_source=share", URL);
        assertEquals(actual, result.url);
        assertTrue(result.html.contains(actual));
    }

    @Test public void duplicateHiddenAndBodyCanonicalsCannotDisappearBeforeValidation() throws Exception {
        for (String html : List.of(
            HTML.replace("</head>", "<link hidden rel='alternate CANONICAL' href='" + URL + "'></head>"),
            HTML.replace("</body>", "<link hidden rel='canonical' href='" + URL + "'></body>"),
            "<html><head></head><body><link rel='canonical' href='" + URL + "'>Продам квартиру</body></html>",
            HTML.replace("IDabc123", "IDanother"), HTML.replace(URL, URL + "?price=1"))) {
            String encoded = callback(html, URL);
            assertThrows(IllegalArgumentException.class, () -> ListingBrowserSnapshot.decode(encoded, URL, URL));
        }
    }

    @Test public void finalBrowserLocationSizeAndVisibleDenialsBoundResult() throws Exception {
        for (String url : List.of(URL.replace("IDabc123", "IDother"), URL.replace("www.", "m."))) {
            String encoded = callback(HTML, url);
            assertThrows(IllegalArgumentException.class, () -> ListingBrowserSnapshot.decode(encoded, URL, URL));
        }
        for (String html : List.of(HTML + "я".repeat(140000), HTML.replace("</body>", "<p>Access denied</p></body>"))) {
            String encoded = callback(html, URL);
            assertThrows(IllegalArgumentException.class, () -> ListingBrowserSnapshot.decode(encoded, URL, URL));
        }
    }
}

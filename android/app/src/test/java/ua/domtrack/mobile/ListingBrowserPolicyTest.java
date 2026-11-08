package ua.domtrack.mobile;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class ListingBrowserPolicyTest {
    private static final String OLX = "https://www.olx.ua/d/uk/obyavlenie/training-apartment-IDabc123.html";
    private static final String RIA = "https://dom.ria.com/uk/realty-prodaja-kvartira-zaporoje-training-35044466.html";

    @Test public void onlyExactHttpsPublicListingDetailsCanStartReader() {
        assertNotNull(ListingBrowserPolicy.target(OLX + "?utm_source=share"));
        assertNotNull(ListingBrowserPolicy.target(RIA));
        assertEquals(OLX, ListingBrowserPolicy.target(OLX.replace("www.", "m.") + "?utm_source=share").url);
        assertEquals("www.olx.ua", ListingBrowserPolicy.target(OLX.replace("www.", "m.")).host);
        assertEquals("dom.ria.com", ListingBrowserPolicy.target(RIA.replace("dom.ria.com", "dim.ria.com")).host);
        for (String url : List.of(
            "https://www.olx.ua/uk/nedvizhimost/", "https://dom.ria.com/uk/search/",
            OLX.replace("https:", "http:"), OLX.replace("www.olx.ua", "olx.ua.evil.test"),
            OLX.replace("www.olx.ua", "user@www.olx.ua"), OLX.replace("www.olx.ua", "www.olx.ua:443"),
            OLX + "#detail", OLX.replace("training-apartment", "training%2fapartment"),
            RIA.replace("realty-prodaja", "realty-arenda"), "file:///tmp/"))
            assertNull(url, ListingBrowserPolicy.target(url));
    }

    @Test public void topNavigationCannotChangeHostPathOrListingIdentity() {
        ListingBrowserPolicy policy = new ListingBrowserPolicy(OLX);
        assertTrue(policy.navigation(OLX));
        assertFalse(policy.navigation(OLX + "?login=1"));
        for (String url : List.of(OLX.replace("www.", "m."), OLX.replace("abc123", "other"),
            OLX.replace("training-apartment", "different-title"), "https://www.olx.ua/account/", "javascript:alert(1)"))
            assertFalse(url, policy.navigation(url));
        assertTrue(policy.sameListing(OLX.replace("www.", "m.")));
        assertFalse(policy.sameListing(OLX.replace("abc123", "other")));
        ListingBrowserPolicy aliasInput = new ListingBrowserPolicy(OLX.replace("www.", "m."));
        assertTrue(aliasInput.navigation(OLX));
        assertFalse(aliasInput.navigation(OLX.replace("www.", "m.")));
    }

    @Test public void resourcePolicyStopsPhotosMediaPrivateEndpointsAndWrites() {
        ListingBrowserPolicy policy = new ListingBrowserPolicy(OLX);
        for (String url : List.of("https://cdn.olxcdn.com/photo.jpg?size=1", "https://cdn.olxcdn.com/clip.mp4",
            "https://www.olx.ua/api/private", "https://www.olx.ua/account/login", "file:///photo.jpg",
            "https://localhost/app.js", "https://192.168.1.1/app.js", "https://unrelated.test/app.js",
            "https://olxcdn.com.evil.test/app.js", "https://images.olxcdn.com/v1/files/example/image;s=1000x700",
            "https://images.olxcdn.com/v1/files/example/%69mage%3bs=1000x700"))
            assertTrue(url, policy.blockResource(url, "GET", ""));
        assertTrue(policy.blockResource("https://cdn.olxcdn.com/resource", "GET", "image/avif,image/webp,*/*"));
        assertTrue(policy.blockResource("https://www.olx.ua/data", "POST", "application/json"));
        assertFalse(policy.blockResource("https://cdn.olxcdn.com/app.js", "GET", "*/*"));
        assertFalse(policy.blockResource("https://cdn.olxcdn.com/app.css", "GET", "text/css,*/*"));
        ListingBrowserPolicy ria = new ListingBrowserPolicy(RIA);
        assertFalse(ria.blockResource("https://cdn.riastatic.com/app.js", "GET", "*/*"));
        assertTrue(ria.blockResource("https://cdn.olxcdn.com/app.js", "GET", "*/*"));
    }
}

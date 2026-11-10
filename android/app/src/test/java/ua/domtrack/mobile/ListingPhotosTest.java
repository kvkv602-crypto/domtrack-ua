package ua.domtrack.mobile;

import static org.junit.Assert.*;
import java.util.*;
import java.io.*;
import org.json.*;
import org.junit.Test;

public class ListingPhotosTest {
    @Test public void downloadsAreBoundedEvenWhenTheServerOmitsContentLength() throws Exception {
        assertArrayEquals(new byte[] { 1, 2, 3 }, ListingPhotos.read(new ByteArrayInputStream(new byte[] { 1, 2, 3 }), -1));
        assertThrows(IOException.class, () -> ListingPhotos.read(new ByteArrayInputStream(new byte[1]), 3L * 1024 * 1024 + 1));
        assertThrows(IOException.class, () -> ListingPhotos.read(new ByteArrayInputStream(new byte[3 * 1024 * 1024 + 1]), -1));
    }

    @Test public void imageSamplingBoundsDecodedDimensionsAndRejectsInvalidHeaders() throws Exception {
        assertEquals(4, ListingPhotos.sampleSize(4096, 2048));
        assertEquals(1, ListingPhotos.sampleSize(1280, 640));
        assertEquals(2, ListingPhotos.sampleSize(1281, 641));
        assertThrows(IOException.class, () -> ListingPhotos.sampleSize(0, 100));
        assertThrows(IOException.class, () -> ListingPhotos.sampleSize(40000, 40000));
    }

    @Test public void sourceUrlsStaySeparateFromPersonalPhotosAndAreDeduplicated() {
        JSONObject row = Json.object("image", "https://cdn.example.org/front.jpg", "remotePhotos",
            new JSONArray().put("https://cdn.example.org/front.jpg").put("https://cdn.example.org/kitchen.jpg"),
            "photos", new JSONArray().put("https://personal.example.org/private.jpg").put("base64-personal-photo"));
        String before = row.toString();
        assertEquals(List.of("https://cdn.example.org/front.jpg", "https://cdn.example.org/kitchen.jpg"), ListingPhotos.urls(row));
        assertEquals(before, row.toString());
    }

    @Test public void unsafeUrlsCannotBecomeRemotePhotoRequests() {
        for (String url : List.of("http://cdn.example.org/a.jpg", "file:///a.jpg", "data:image/png;base64,a",
            "https://user:password@cdn.example.org/a.jpg", "https://cdn.example.org:8443/a.jpg",
            "https://127.0.0.1/a.jpg", "https://10.0.0.1/a.jpg", "https://[::1]/a.jpg",
            "https://[::ffff:127.0.0.1]/a.jpg", "https://2130706433/a.jpg", "https://localhost/a.jpg",
            "https://router.local/a.jpg", "https://cdn.example.org/a.jpg#fragment", "https://cdn.example.org/\\a.jpg"))
            assertTrue(url, ListingPhotos.urls(Json.object("image", url)).isEmpty());
        assertEquals(List.of("https://cdn.example.org/a.jpg?size=large&token=public"), ListingPhotos.urls(
            Json.object("image", "https://cdn.example.org/a.jpg?size=large&token=public")));
    }

    @Test public void explicitSchemaImageObjectsAndRelativeImagePathsAreNormalizedWithoutGuessing() {
        JSONObject row = new JSONObject();
        ListingPhotos.metadata(row, new JSONArray().put(Json.object("@type", "ImageObject", "contentUrl", "/front.jpg"))
            .put(Json.object("url", "https://cdn.example.org/kitchen.webp"))
            .put(Json.object("photo_id", 123456)).put("123456").put("opaque-photo-id")
            .put("javascript:alert(1)"), "https://example.org/listing/1");
        assertEquals(List.of("https://example.org/front.jpg", "https://cdn.example.org/kitchen.webp"), ListingPhotos.urls(row));
        assertEquals("https://example.org/front.jpg", row.optString("image"));
    }

    @Test public void galleriesHaveABoundedNumberOfUniqueSourcePhotos() {
        JSONArray urls = new JSONArray();
        for (int i = 0; i < 50; i++) urls.put("https://cdn.example.org/photo-" + i + ".jpg");
        JSONObject row = new JSONObject();
        ListingPhotos.metadata(row, urls, "");
        List<String> photos = ListingPhotos.urls(row);
        assertEquals(12, photos.size());
        assertEquals("https://cdn.example.org/photo-0.jpg", photos.get(0));
        assertEquals("https://cdn.example.org/photo-11.jpg", photos.get(11));
    }

    @Test public void riaGalleryBelongsToTheCanonicallyMatchedProductOnly() {
        String url = "https://dom.ria.com/uk/realty-prodaja-kvartira-training-35044466.html";
        JSONObject product = Json.object("@type", "Product", "@id", url, "name", "Квартира",
            "image", new JSONArray().put("https://cdn.example.org/front.jpg")
                .put(Json.object("@type", "ImageObject", "contentUrl", "https://cdn.example.org/room.jpg")),
            "offers", Json.object("businessFunction", "sell", "url", url, "price", 15000,
                "priceCurrency", "USD", "areaServed", "Запоріжжя"));
        JSONObject property = Json.object("@type", "Apartment", "url", url, "numberOfRooms", 2);
        JSONObject related = Json.object("@type", "Product", "@id", url.replace("35044466", "35044467"),
            "image", "https://cdn.example.org/related.jpg");
        String html = "<script type='application/ld+json'>" + new JSONArray().put(product).put(property).put(related) + "</script>";
        JSONObject row = Sources.structured(html, url).get(0);
        assertEquals(List.of("https://cdn.example.org/front.jpg", "https://cdn.example.org/room.jpg"), ListingPhotos.urls(row));
    }

    @Test public void olxUsesItsVisiblePropertyGalleryInsteadOfUnrelatedPageImages() {
        String html = OlxDetailTest.fixture().replace("</body>",
            "<section data-testid='photo-gallery'><img src='https://cdn.example.org/front.jpg'>" +
            "<img data-src='https://cdn.example.org/room.jpg' src='data:image/gif;base64,a'>" +
            "<img hidden src='https://cdn.example.org/hidden.jpg'></section>" +
            "<aside data-testid='recommended-ads'><div data-testid='swiper'>" +
            "<img src='https://cdn.example.org/other.jpg'></div>" +
            "<img data-testid='ad-photo' src='https://cdn.example.org/recommended.jpg'></aside>" +
            "<img data-testid='swiper-image' src='https://cdn.example.org/unscoped.jpg'>" +
            "<img src='https://cdn.example.org/related.jpg'></body>");
        JSONObject row = Sources.structured(html, OlxDetailTest.URL).get(0);
        assertEquals(List.of("https://cdn.example.org/front.jpg", "https://cdn.example.org/room.jpg"), ListingPhotos.urls(row));
    }

    @Test public void catalogLazyImageIsScopedToItsListingCard() {
        Sources.Section section = Sources.sections("Запоріжжя", "apartment").stream()
            .filter(s -> s.source.equals("olx")).findFirst().orElseThrow();
        String html = "<link rel='canonical' href='" + section.base + "'>" +
            "<img src='https://cdn.example.org/page-logo.jpg'><div data-cy='l-card'>" +
            "<a href='https://www.olx.ua/d/uk/obyavlenie/training-IDabc123.html'><h4>Продам квартиру</h4></a>" +
            "<p data-testid='ad-price'>15 000 $</p><p data-testid='location-date'>Запоріжжя - Сьогодні</p>" +
            "<img data-src='https://cdn.example.org/listing.jpg' src='data:image/gif;base64,a'></div>";
        JSONObject row = Sources.parse(html, section.base, section, 1).records.get(0);
        assertEquals(List.of("https://cdn.example.org/listing.jpg"), ListingPhotos.urls(row));
    }
}

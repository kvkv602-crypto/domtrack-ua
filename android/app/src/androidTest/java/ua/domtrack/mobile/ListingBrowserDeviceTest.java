package ua.domtrack.mobile;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.view.ViewGroup;
import android.webkit.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayInputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;

/** Real Chromium DOM checks with synthetic content only; no request is sent to a public site. */
@RunWith(AndroidJUnit4.class)
public class ListingBrowserDeviceTest {
    private static final String URL = "https://www.olx.ua/d/uk/obyavlenie/training-apartment-IDabc123.html";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private Activity host;
    private WebView page;
    private final AtomicInteger photoRequests = new AtomicInteger();

    @BeforeClass public static void isolateSyntheticProfile() {
        WebView.setDataDirectorySuffix("listing_reader_synthetic_test");
    }

    @Before public void openHost() {
        host = instrumentation.startActivitySync(new Intent(
            instrumentation.getTargetContext(), MainActivity.class
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @After public void closeHost() {
        instrumentation.runOnMainSync(() -> {
            if (page != null) {
                page.stopLoading();
                page.clearCache(true);
                if (page.getParent() instanceof ViewGroup parent) parent.removeView(page);
                page.destroy();
                page = null;
            }
            CookieManager.getInstance().removeAllCookies(null);
            WebStorage.getInstance().deleteAllData();
            if (host != null) host.finish();
        });
        instrumentation.waitForIdleSync();
    }

    private String fixture() {
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width'>" +
            "<link rel='canonical' href='" + URL + "'><style>.css-hidden{display:none}</style></head><body>" +
            "<nav data-testid='breadcrumbs'><a href='https://www.olx.ua/uk/nedvizhimost/kvartiry/prodazha-kvartir/zaporozhe/'>Продаж квартир - Запоріжжя</a></nav>" +
            "<section data-testid='offer_title'><h4>Продам двокімнатну квартиру</h4></section>" +
            "<section data-testid='ad-price-container'><h3>14 500 $</h3></section>" +
            "<section data-testid='ad-parameters-container'><p>Поверх: 4</p><p>Поверховість: 5</p>" +
            "<p>Загальна площа: 46 м²</p><p>Кількість кімнат: 2 кімнати</p></section>" +
            "<section data-testid='ad_description'>Навчальний опис квартири.</section>" +
            "<div class='css-hidden' data-testid='ad-price-container'><h3>999999 $</h3></div>" +
            "<div style='opacity:0' data-testid='ad-price-container'><h3>888888 $</h3></div>" +
            "<div aria-hidden='TRUE' data-testid='ad-price-container'><h3>777777 $</h3></div>" +
            "<script>window.__BOOTSTRAP__={price:111111,privateToken:'runtime-secret'};</script>" +
            "<input value='form-secret'><img src='https://images.olxcdn.com/photo.jpg'></body></html>";
    }

    private void load(String html, String url) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> {
            page = new WebView(host);
            ListingBrowserActivity.configure(page);
            page.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String finishedUrl) {
                    view.postVisualStateCallback(1, new WebView.VisualStateCallback() {
                        @Override public void onComplete(long requestId) { loaded.countDown(); }
                    });
                }
                @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    if (request.getUrl().toString().contains("photo.jpg")) photoRequests.incrementAndGet();
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                }
            });
            host.addContentView(page, new ViewGroup.LayoutParams(-1, -1));
            page.loadDataWithBaseURL(url, html, "text/html", "UTF-8", null);
        });
        assertTrue("Synthetic page did not render", loaded.await(15, TimeUnit.SECONDS));
    }

    private String evaluate(String script) throws Exception {
        AtomicReference<String> value = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> page.evaluateJavascript(script, result -> {
            value.set(result); ready.countDown();
        }));
        assertTrue("Synthetic DOM export did not finish", ready.await(5, TimeUnit.SECONDS));
        return value.get();
    }

    @Test public void visibleSnapshotImportsScopedPriceWithoutHiddenRuntimeOrPhotos() throws Exception {
        load(fixture(), URL);
        String encoded = evaluate(ListingBrowserSnapshot.script(false));
        ListingBrowserSnapshot.Result result = ListingBrowserSnapshot.decode(encoded, URL, URL);
        JSONObject row = Sources.importRecords(result.html, URL, result.url).get(0);
        assertEquals(14500, row.optDouble("price"), 0);
        assertEquals(46, row.optDouble("area"), 0);
        assertEquals(URL, result.url);
        for (String forbidden : new String[]{"999999", "888888", "777777", "runtime-secret", "form-secret", "photo.jpg", "__BOOTSTRAP__"})
            assertFalse(forbidden, result.html.contains(forbidden));
        assertEquals("Image loading must remain disabled", 0, photoRequests.get());
        assertEquals("false", evaluate("document.querySelector('img').naturalWidth>0"));
    }

    @Test public void hiddenDuplicateCanonicalIsRetainedAndRejected() throws Exception {
        load(fixture().replace("</head>", "<link hidden rel='alternate CANONICAL' href='" + URL.replace("abc123", "other") + "'></head>"), URL);
        String encoded = evaluate(ListingBrowserSnapshot.script(false));
        assertThrows(IllegalArgumentException.class, () -> ListingBrowserSnapshot.decode(encoded, URL, URL));
    }

    @Test public void riaSnapshotKeepsOnlyPublicJsonLdAndActualUrlBinding() throws Exception {
        String ria = "https://dom.ria.com/uk/realty-prodaja-kvartira-zaporoje-training-35044466.html";
        String html = fixture().replace(URL, ria).replace("</head>",
            "<script type='application/ld+json'>{\"@type\":\"Apartment\",\"name\":\"Public listing\"}</script></head>");
        load(html, ria);
        ListingBrowserSnapshot.Result result = ListingBrowserSnapshot.decode(evaluate(ListingBrowserSnapshot.script(true)), ria, ria);
        assertTrue(result.html.contains("application/ld+json"));
        assertTrue(result.html.contains("Public listing"));
        assertFalse(result.html.contains("runtime-secret"));
        String encoded = evaluate(ListingBrowserSnapshot.script(true));
        assertThrows(IllegalArgumentException.class, () -> ListingBrowserSnapshot.decode(encoded, ria, ria.replace("35044466", "35044467")));
    }
}

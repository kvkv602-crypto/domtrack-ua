package ua.domtrack.mobile;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real public REM catalogs through the app's normal DNS, robots, HTTPS and response-size rules. */
@RunWith(AndroidJUnit4.class)
public class RemDeviceTest {
    @Test public void publicSaleCatalogsLoadOnPhoneAndAdvanceToDistinctSecondPage() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Database appDb = ((DomTrackApp) context.getApplicationContext()).db();
        String fixture = "rem-public-policy-test.db";
        context.deleteDatabase(fixture);
        Database policy = new Database(context, fixture);
        Network network = new Network();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        long started = android.os.SystemClock.elapsedRealtime();
        appDb.saveState("remDeviceTest", Json.object("status", "running"));
        Future<JSONObject> task = executor.submit(() -> {
            JSONObject report = Json.object("status", "running", "robotsAllowed", true);
            Sources.Section apartment = RemPage.section("Запоріжжя", "Запорізька", "apartment");
            Sources.Section house = RemPage.section("Запоріжжя", "Запорізька", "house");
            assertNotNull(apartment);
            assertNotNull(house);
            assertTrue("REM robots must allow the public sale catalog", network.robotsAllowed(apartment.base, policy));
            Network.Page page = network.get(apartment.base);
            Sources.Result first = Sources.parse(page.body, page.url, apartment, 1);
            checked(first.records, "apartment");
            Json.put(report, "apartmentCards", first.cards);
            Json.put(report, "apartments", first.records.size());
            Json.put(report, "apartmentPhotos", first.records.stream().filter(row -> !ListingPhotos.urls(row).isEmpty()).count());
            Json.put(report, "apartmentCoordinates", first.records.stream().filter(row -> row.has("latitude") && row.has("longitude")).count());
            appDb.saveState("remDeviceTest", report);
            Element next = Jsoup.parse(page.body, page.url).selectFirst(".pagination a[rel=next]");
            Json.put(report, "secondPageAvailable", next != null);
            if (next != null) {
                String url = next.absUrl("href");
                assertTrue("REM robots must also allow the public next page", network.robotsAllowed(url, policy));
                Network.Page secondPage = network.get(url);
                Sources.Result second = Sources.parse(secondPage.body, secondPage.url, apartment, 2);
                checked(second.records, "apartment");
                Set<String> ids = new HashSet<>();
                for (JSONObject row : first.records) ids.add(row.optString("externalId"));
                long novel = second.records.stream().filter(row -> !ids.contains(row.optString("externalId"))).count();
                assertNotEquals("A repeated first page cannot count as pagination", first.fingerprint, second.fingerprint);
                assertTrue("The real second page must contain distinct property IDs", novel > 0);
                Json.put(report, "apartmentPage2", second.records.size());
                Json.put(report, "newSecondPageIds", novel);
            } else assertTrue("Without a next link only a proven final page can finish", first.end);
            appDb.saveState("remDeviceTest", report);
            assertTrue("REM robots must allow the public house catalog", network.robotsAllowed(house.base, policy));
            Network.Page housePage = network.get(house.base);
            Sources.Result houses = Sources.parse(housePage.body, housePage.url, house, 1);
            checked(houses.records, "house");
            Json.put(report, "houseCards", houses.cards);
            Json.put(report, "houses", houses.records.size());
            Json.put(report, "housePhotos", houses.records.stream().filter(row -> !ListingPhotos.urls(row).isEmpty()).count());
            Json.put(report, "houseCoordinates", houses.records.stream().filter(row -> row.has("latitude") && row.has("longitude")).count());
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            Json.put(report, "status", "checked");
            return report;
        });
        try {
            JSONObject report = task.get(90, TimeUnit.SECONDS);
            Json.put(report, "elapsedMillis", android.os.SystemClock.elapsedRealtime() - started);
            appDb.saveState("remDeviceTest", report);
        } catch (Exception | AssertionError error) {
            network.cancel();
            task.cancel(true);
            JSONObject report = appDb.state("remDeviceTest", new JSONObject());
            Json.put(report, "status", "failed");
            Json.put(report, "failureType", error.getClass().getSimpleName());
            Json.put(report, "elapsedMillis", android.os.SystemClock.elapsedRealtime() - started);
            appDb.saveState("remDeviceTest", report);
            throw error;
        } finally {
            network.cancel();
            task.cancel(true);
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            policy.close();
            context.deleteDatabase(fixture);
        }
    }

    private static void checked(List<JSONObject> rows, String kind) {
        assertFalse("A denied or changed source must fail instead of reporting an empty success", rows.isEmpty());
        for (JSONObject row : rows) {
            assertEquals("rem", row.optString("source"));
            assertEquals("Запоріжжя", row.optString("city"));
            assertEquals(kind, row.optString("kind"));
            assertEquals("sale", row.optString("deal"));
            assertEquals("display", row.optString("priceEvidence"));
            assertFalse(Model.verifiedPrice(row));
            assertTrue(row.optDouble("price") > 0);
            assertTrue(row.optString("externalId").matches("[1-9][0-9]+"));
        }
    }
}

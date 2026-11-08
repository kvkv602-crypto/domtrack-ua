package ua.domtrack.mobile;

import static org.junit.Assert.*;
import java.util.*;
import org.json.*;
import org.junit.Test;

public class RiaWatchTest {
    private static JSONObject payload() {
        return Json.object("realty_id", "12345678", "operation_type", 1, "realty_type", 2,
            "price_total", 14500, "currency_type", "USD", "city_name", "Запоріжжя",
            "beautiful_url", "uk/realty-prodaja-kvartira-zaporozhe-new-12345678.html");
    }

    private static JSONObject previous() {
        return Json.object("source", "domria", "externalId", "12345678", "kind", "apartment",
            "city", "Запоріжжя", "deal", "sale", "title", "Сохранённая квартира", "price", 20000,
            "currency", "USD", "url", "https://dom.ria.com/uk/realty-prodaja-kvartira-zaporozhe-old-12345678.html?reason=share");
    }

    @Test public void apiConfirmedSlugChangeRefreshesTheOriginalHistoryWithNativePrice() {
        JSONObject old=previous(), fresh=Ria.watched(payload(), old);
        assertEquals(14500, fresh.optDouble("price"), 0);
        assertEquals("native", fresh.optString("priceEvidence"));
        assertEquals("domria", fresh.optString("source"));
        assertEquals("12345678", fresh.optString("externalId"));
        assertEquals(old.optString("url"), fresh.optString("url"));
        assertEquals(Model.identity(old), Model.identity(fresh));
        assertEquals(20000, old.optDouble("price"), 0);
        assertTrue(Model.verifiedPrice(fresh));
    }

    @Test public void anotherIdCityKindOrUrlCannotRefreshTheTarget() {
        for (String field : List.of("realty_id", "city_name", "realty_type", "operation_type", "beautiful_url")) {
            JSONObject data=payload();
            Json.put(data, field, switch(field) {
                case "realty_id" -> "87654321";
                case "city_name" -> "Київ";
                case "realty_type" -> 5;
                case "operation_type" -> 2;
                default -> "realty-prodaja-kvartira-other-87654321.html";
            });
            assertThrows(field, IllegalArgumentException.class, ()->Ria.watched(data, previous()));
        }
        JSONObject missing=payload(); missing.remove("city_name");
        assertThrows(IllegalArgumentException.class, ()->Ria.watched(missing, previous()));
    }

    @Test public void invalidOriginalUrlAndPublicSourceCannotBeMigratedToApiHistory() {
        for (String url : List.of("https://dom.ria.com/uk/realty-87654321.html",
            "https://dom.ria.com.evil.example/uk/realty-12345678.html", "https://dom.ria.com/uk/")) {
            JSONObject old=previous(); Json.put(old, "url", url);
            assertThrows(IllegalArgumentException.class, ()->Ria.watched(payload(), old));
        }
        JSONObject publicRow=previous(); Json.put(publicRow, "source", "domria-web");
        assertFalse(Ria.nativeWatch(publicRow));
        assertThrows(IllegalArgumentException.class, ()->Ria.watched(payload(), publicRow));
    }

    @Test public void verifiedApiIdentitySurvivesPublicObservationWithoutPromotingItsPrice() {
        JSONObject api=Ria.normalize(payload(), "12345678", "Запоріжжя", "apartment");
        JSONObject stored=Json.copy(api);
        Ria.keepApiIdentity(null, stored, api);
        JSONObject catalog=Json.copy(stored);
        Json.put(catalog, "source", "domria-web"); catalog.remove("externalId");
        Json.put(catalog, "price", 16000); Json.put(catalog, "priceEvidence", "display");
        Ria.keepApiIdentity(stored, catalog, catalog);
        assertTrue(Ria.nativeWatch(catalog));
        assertFalse(Model.verifiedPrice(catalog));
        JSONObject fresh=Ria.watched(payload(), catalog);
        assertEquals("domria-web", fresh.optString("source"));
        assertEquals("native", fresh.optString("priceEvidence"));
        assertEquals(Model.identity(stored), Model.identity(fresh));
    }

    @Test public void jsonBackupAndPublicInputCannotEstablishApiIdentity() {
        JSONObject api=Ria.normalize(payload(), "12345678", "Запоріжжя", "apartment");
        JSONObject stored=Json.copy(api); Ria.keepApiIdentity(null, stored, api);
        assertNotNull(stored.optJSONObject("riaApiIdentity"));
        JSONObject backup=Json.copy(stored);
        Ria.keepApiIdentity(null, backup, backup);
        assertFalse(backup.has("riaApiIdentity"));
        JSONObject forged=Json.copy(stored);
        Json.put(forged, "source", "domria-web");
        Ria.keepApiIdentity(null, forged, forged);
        assertFalse(forged.has("riaApiIdentity")); assertFalse(Ria.nativeWatch(forged));
    }

    @Test public void retainedApiIdentityCannotMoveToAnotherUrlCityKindOrAggregator() {
        JSONObject api=Ria.normalize(payload(), "12345678", "Запоріжжя", "apartment");
        JSONObject stored=Json.copy(api); Ria.keepApiIdentity(null, stored, api);
        for(String field:List.of("url", "city", "kind", "source")) {
            JSONObject next=Json.copy(stored); Json.put(next, "source", "domria-web");
            Json.put(next, field, switch(field) {
                case "url" -> "https://dom.ria.com/uk/realty-87654321.html";
                case "city" -> "Київ";
                case "kind" -> "house";
                default -> "flatfy";
            });
            Ria.keepApiIdentity(stored, next, next);
            assertFalse(field, next.has("riaApiIdentity")); assertFalse(field, Ria.nativeWatch(next));
        }
    }
}

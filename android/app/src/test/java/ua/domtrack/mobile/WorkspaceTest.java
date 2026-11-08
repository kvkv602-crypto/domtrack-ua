package ua.domtrack.mobile;

import static org.junit.Assert.*;
import java.util.*;
import org.json.*;
import org.junit.Test;

public class WorkspaceTest {
    @Test public void favoritesIgnoreSearchCityBudgetAndPolygon() throws Exception {
        JSONObject current=Json.object("city","Київ","kind","house","priceMax",100,"query","неподходящий запрос","polygon",new JSONArray("[[1,1],[2,2],[3,3]]"),"currency","USD");
        JSONObject result=Workspace.resultFilters(current,true);
        assertTrue(result.optBoolean("favorite"));
        for(String key:List.of("city","kind","priceMax","query","polygon"))assertFalse(key,result.has(key));
        JSONObject other=Json.object("favorite",true,"city","Запоріжжя","kind","apartment","price",14500,"currency","USD");
        assertTrue(Model.matches(other,result,new JSONObject()));
        assertEquals("Київ",current.optString("city"));
        assertEquals("Київ",Workspace.resultFilters(current,false).optString("city"));
    }
    @Test public void savedWatchCurrencyAndSafeSortAreRetained() {
        assertEquals(1,Workspace.watchCurrencyIndex(Json.object("currency","USD")));
        assertEquals(2,Workspace.watchCurrencyIndex(Json.object("currency","UAH")));
        assertEquals(0,Workspace.watchCurrencyIndex(null));
        assertEquals("price",Workspace.sort(Json.object("sort","unknown")));
        assertEquals("newest",Workspace.sort(Json.object("sort","newest")));
    }
    @Test public void editingSearchKeepsIdentityAndNotificationChoiceWithoutMutatingOriginal() {
        JSONObject original=Json.object("id","saved-7","name","Прежний","enabled",false,"filters",Json.object("city","Київ"));
        JSONObject next=Workspace.savedSearch(original,"Новое название",Json.object("city","Запоріжжя"));
        assertEquals("saved-7",next.optString("id"));assertFalse(next.optBoolean("enabled",true));
        assertEquals("Новое название",next.optString("name"));assertEquals("Запоріжжя",next.optJSONObject("filters").optString("city"));
        assertEquals("Київ",original.optJSONObject("filters").optString("city"));
        assertTrue(Workspace.savedSearch(null,"Новый",new JSONObject()).optBoolean("enabled"));
    }
    @Test public void coverageCountsProvidersRatherThanDoubleCountingHouseAndApartmentSections() {
        String summary=Workspace.coverage(List.of(Json.object("source","olx","error","HTTP 403"),Json.object("source","olx","checkedAt","2026-11-08","complete",true),Json.object("source","rieltor","checkedAt","2026-11-08","complete",false),Json.object("source","domria")));
        assertTrue(summary,summary.contains("ошибки: 1"));assertTrue(summary,summary.contains("частично: 1"));assertTrue(summary,summary.contains("не проверены: 1"));
    }
    @Test public void displayPriceChangesCannotBecomeVerifiedDropOrThresholdAlerts() {
        JSONObject before=Json.object("price",15000,"currency","USD","title","Квартира"),next=Json.object("price",14000,"currency","USD","title","Квартира","priceEvidence","display"),watch=Json.object("target",14500,"currency","USD");
        assertFalse(Model.eventTypes(before,next,true,watch,new JSONObject()).contains("price"));
        assertFalse(Model.verifiedPrice(Json.object("source","domria-web","price",14500)));
        assertFalse(Model.verifiedPrice(Json.object("source","web:dom.ria.com","price",14500)));
        assertTrue(Model.verifiedPrice(Json.object("source","domria","priceEvidence","native","price",14500)));
        assertFalse(Model.eventTypes(before,next,true,watch,new JSONObject()).contains("threshold"));
        Json.put(before,"priceEvidence","display");next.remove("priceEvidence");
        assertFalse(Model.eventTypes(before,next,true,watch,new JSONObject()).contains("price"));
    }
    @Test public void partialCatalogAndDetailDoNotEraseFactsOrInventObjectChanges() {
        JSONObject catalog=Json.object("title","Учебная квартира","price",14500,"currency","USD","image","https://example.org/old.jpg");
        JSONObject detail=Json.object("title","Учебная квартира","price",14500,"currency","USD","rooms",2,"area",46,"floor",4,"floorsTotal",5,"description","Проверенный открытый текст");
        Model.keepKnownFacts(catalog,detail);
        assertEquals(catalog.optString("image"),detail.optString("image"));
        assertFalse(Model.eventTypes(catalog,detail,true,null,new JSONObject()).contains("object"));
        JSONObject again=Json.copy(catalog);Model.keepKnownFacts(detail,again);
        assertEquals(2,again.optInt("rooms"));assertEquals(5,again.optInt("floorsTotal"));
        assertFalse(Model.eventTypes(detail,again,true,null,new JSONObject()).contains("object"));
        Json.put(again,"image","https://example.org/old.jpg?resize=mobile");
        assertFalse(Model.eventTypes(detail,again,true,null,new JSONObject()).contains("object"));
        Json.put(again,"rooms",3);assertTrue(Model.eventTypes(detail,again,true,null,new JSONObject()).contains("object"));
    }
    @Test public void webDisplayCannotInheritAnOlderNativeApiPriceMarker() {
        JSONObject api=Json.object("source","domria","priceEvidence","native","price",15000);
        JSONObject web=Json.object("source","domria-web","price",14000);
        Model.keepPriceEvidence(api,web);assertEquals("display",web.optString("priceEvidence"));assertFalse(Model.verifiedPrice(web));
        JSONObject sparse=Json.object("source","training","price",14000);
        Model.keepPriceEvidence(Json.object("source","training","priceEvidence","display"),sparse);assertEquals("display",sparse.optString("priceEvidence"));
        Model.keepPriceEvidence(web,api);assertTrue(Model.verifiedPrice(api));
    }
    @Test public void publicJsonLdHostSourcesCannotClaimNativeSellerPrice() {
        JSONObject api=Json.object("source","domria","priceEvidence","native","price",15000,"currency","USD");
        for(String source:List.of("dom.ria.com","dim.ria.com","www.olx.ua","olx.ua","m.olx.ua","web:dim.ria.com","DOM.RIA.COM","WWW.OLX.UA")) {
            JSONObject imported=Json.object("source",source,"price",14000,"currency","USD");
            assertFalse(source,Model.verifiedPrice(imported));
            Model.keepPriceEvidence(api,imported);
            assertEquals(source,"display",imported.optString("priceEvidence"));
            assertFalse(Model.eventTypes(api,imported,true,Json.object("target",14500,"currency","USD"),new JSONObject()).contains("threshold"));
        }
    }
    @Test public void apiGeneratedTitlesDoNotReplaceRealHeadlinesOrCreateObjectAlerts() {
        JSONObject api=Json.object("source","domria","title","Квартира · Запоріжжя","titleEvidence","generated","city","Запоріжжя","kind","apartment","price",14500,"currency","USD");
        JSONObject catalog=Json.object("source","domria-web","title","Продаж квартири на вулиці Навчальній","city","Запоріжжя","kind","apartment","price",14500,"currency","USD");
        assertFalse(Model.eventTypes(api,catalog,true,null,new JSONObject()).contains("object"));
        JSONObject fresh=Json.copy(api);Model.keepKnownFacts(catalog,fresh);
        assertEquals(catalog.optString("title"),fresh.optString("title"));
        assertFalse(fresh.has("titleEvidence"));
        assertFalse(Model.eventTypes(catalog,fresh,true,null,new JSONObject()).contains("object"));
        JSONObject legacy=Json.copy(api);legacy.remove("titleEvidence");
        assertFalse(Model.eventTypes(legacy,catalog,true,null,new JSONObject()).contains("object"));
        JSONObject renamed=Json.copy(catalog);Json.put(renamed,"title","Изменённый содержательный заголовок");
        assertTrue(Model.eventTypes(catalog,renamed,true,null,new JSONObject()).contains("object"));
    }
}

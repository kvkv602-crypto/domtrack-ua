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
}

package ua.domtrack.mobile;

import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import java.util.*;

public class CoreTest {
    private JSONObject listing(){return Json.object("url","https://example.org/flat/123","title","Квартира у парка","city","Запоріжжя","kind","apartment","deal","sale","price",12000,"currency","USD","area",50,"rooms",2,"floor",4,"floorsTotal",9);}
    private JSONObject quote(){return Json.object("date",Model.date(),"rates",Json.object("UAH",1,"USD",40,"EUR",50));}
    @Test public void emptyBudgetWorksInAllCurrencies(){for(String currency:List.of("USD","EUR","UAH"))assertTrue(Model.matches(listing(),Json.object("currency",currency),null));}
    @Test public void euroBudgetUsesDatedNbuRate(){assertTrue(Model.matches(listing(),Json.object("priceMax",10000,"currency","EUR"),quote()));assertFalse(Model.matches(listing(),Json.object("priceMax",9000,"currency","EUR"),quote()));JSONObject old=quote();Json.put(old,"date","2020-01-01");assertFalse(Model.matches(listing(),Json.object("priceMax",10000,"currency","EUR"),old));}
    @Test public void priceRiseAndDropBothProducePriceEvents(){JSONObject before=listing(),next=listing();Json.put(next,"price",13000);assertTrue(Model.eventTypes(before,next,false,null,quote()).contains("price"));Json.put(next,"price",11000);assertTrue(Model.eventTypes(before,next,false,null,quote()).contains("price"));assertTrue(Model.eventTypes(before,before,true,null,quote()).isEmpty());}
    @Test public void exchangeRateChangeAloneIsNotSellerPriceChange(){assertTrue(Model.eventTypes(listing(),listing(),false,null,quote()).isEmpty());}
    @Test public void thresholdCrossingUsesWatchCurrency(){JSONObject next=listing();Json.put(next,"price",11000);assertTrue(Model.eventTypes(listing(),next,true,Json.object("target",9000,"currency","EUR"),quote()).contains("threshold"));assertFalse(Model.eventTypes(listing(),next,true,Json.object("target",8000,"currency","EUR"),quote()).contains("threshold"));}
    @Test public void fieldChangesNotifyOnlyWatchedObjects(){JSONObject next=listing();Json.put(next,"floor",5);assertTrue(Model.eventTypes(listing(),next,false,null,quote()).isEmpty());assertTrue(Model.eventTypes(listing(),next,true,null,quote()).contains("object"));}
    @Test public void apartmentWithoutRequiredFieldsDoesNotPassFilter(){assertFalse(Model.matches(listing(),Json.object("roomsMin",3),quote()));assertTrue(Model.matches(listing(),Json.object("roomsMin",2,"roomsMax",2),quote()));JSONObject r=listing();r.remove("floor");assertFalse(Model.matches(r,Json.object("floorMin",1),quote()));}
    @Test public void polygonNeverGuessesMissingLocation(){JSONArray polygon=new JSONArray("[[47,35],[48,35],[48,36],[47,36]]");assertTrue(Model.inside(47.5,35.5,polygon));assertFalse(Model.inside(49,35.5,polygon));assertFalse(Model.matches(listing(),Json.object("polygon",polygon),quote()));}
    @Test public void priceParsingRejectsSquareMeterAndConflictingValues(){assertNull(Sources.price("400 $ / м²",false));assertNull(Sources.price("Договорная",false));assertEquals(11000,Sources.price("$⁠11 000",true).optDouble("price"),.001);}
    @Test public void backupIsAuthenticatedAndWrongPasswordFails()throws Exception{JSONObject data=Json.object("records",new JSONArray().put(listing()));byte[] encrypted=Backup.encrypt(data,"test-pass-1234".toCharArray());assertFalse(new String(encrypted,java.nio.charset.StandardCharsets.UTF_8).contains("Квартира"));assertEquals(data.toString(),Backup.decrypt(new String(encrypted,java.nio.charset.StandardCharsets.UTF_8),"test-pass-1234".toCharArray()).toString());try{Backup.decrypt(new String(encrypted,java.nio.charset.StandardCharsets.UTF_8),"wrong-password".toCharArray());fail("Wrong password accepted");}catch(javax.crypto.AEADBadTagException expected){}}
    @Test public void privateNetworksAreRejected()throws Exception{for(String ip:List.of("127.0.0.1","192.168.1.2","10.0.0.1","169.254.169.254","100.64.0.1","::1","fd00::1"))assertFalse(Network.isPublic(java.net.InetAddress.getByName(ip)));assertTrue(Network.isPublic(java.net.InetAddress.getByName("1.1.1.1")));}
    @Test public void robotsLongestRuleAndExplicitAllow(){assertFalse(Network.robots("User-agent: *\nDisallow: /private/","/private/x"));assertTrue(Network.robots("User-agent: *\nDisallow: /private/\nAllow: /private/public/","/private/public/x"));}
}

package ua.domtrack.mobile;

import static org.junit.Assert.*;
import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.io.File;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CombinedWorkspaceDeviceTest {
    private Database db; private Activity activity; private JSONObject oldFilters,oldSettings,oldUi; private String id;
    private Instrumentation instrumentation(){return InstrumentationRegistry.getInstrumentation();}
    @Before public void start() {
        Context c=instrumentation().getTargetContext();db=((DomTrackApp)c.getApplicationContext()).db();
        oldFilters=Json.copy(db.state("currentFilters",new JSONObject()));oldSettings=Json.copy(db.state("settings",new JSONObject()));
        oldUi=Json.copy(db.state("ui",new JSONObject()));db.saveState("ui",Json.object("sort","price"));
        JSONObject settings=Json.copy(oldSettings);Json.put(settings,"background",false);db.saveState("settings",settings);
        db.saveState("currentFilters",Json.object("city","Київ","kind","house","priceMax",1,"query","неподходящие условия","currency","USD"));
        JSONObject row=Json.object("source","training","url","https://example.org/combined-workspace","title","Учебное избранное в Запорожье","city","Запоріжжя","region","Запорізька","kind","apartment","deal","sale","period","total","price",14500,"currency","USD","rooms",2,"floor",4,"floorsTotal",5,"observedAt",Model.now());
        db.ingest(List.of(row),false);id=Model.identity(row);db.local(id,"favorite",true);db.local(id,"hidden",false);db.local(id,"watch",Json.object("enabled",false,"target",14000,"currency","USD"));
        activity=instrumentation().startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));
        instrumentation().waitForIdleSync();
    }
    @After public void restore() {
        instrumentation().runOnMainSync(()->{if(activity!=null)activity.finish();});
        if(db!=null){db.local(id,"hidden",true);db.local(id,"watch",Json.object("enabled",false));db.saveState("currentFilters",oldFilters);db.saveState("settings",oldSettings);db.saveState("ui",oldUi);}
    }
    private View find(View root,String label) {
        if(root instanceof TextView&&((TextView)root).getText().toString().equals(label))return root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=find(((ViewGroup)root).getChildAt(i),label);if(found!=null)return found;}
        return null;
    }
    private void tap(String label){instrumentation().runOnMainSync(()->{View v=find(activity.findViewById(android.R.id.content),label);assertNotNull(label,v);assertTrue(v.performClick());});instrumentation().waitForIdleSync();}
    @Test public void emptyAndUnknownCityRenderAndOpenSourceStatusWithoutChangingSavedFilters() {
        UiDevice device=UiDevice.getInstance(instrumentation());
        for(JSONObject filters:List.of(new JSONObject(),Json.object("city","Несуществующий город","kind","apartment","currency","USD"))) {
            instrumentation().runOnMainSync(activity::finish);
            instrumentation().waitForIdleSync();
            db.saveState("currentFilters",filters);
            String original=filters.toString();
            activity=instrumentation().startActivitySync(new Intent(instrumentation().getTargetContext(),MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            instrumentation().waitForIdleSync();
            instrumentation().runOnMainSync(()->assertNotNull(find(activity.findViewById(android.R.id.content),"Выбери город в фильтрах, чтобы увидеть состояние источников.")));
            tap("Источники и прогресс");
            assertTrue(device.wait(Until.hasObject(By.text("Охват источников")),5000));
            assertTrue(device.hasObject(By.text("Выбери город в фильтрах, чтобы увидеть состояние источников.")));
            device.pressBack();
            assertEquals(original,db.state("currentFilters",new JSONObject()).toString());
        }
        JSONObject report=db.state("combinedWorkspaceDeviceTest",new JSONObject());Json.put(report,"invalidCityUiSafe",true);db.saveState("combinedWorkspaceDeviceTest",report);
    }
    @Test public void favoritesRemainVisibleAcrossSearchCityBudgetAndSort() {
        tap("Избранное");
        instrumentation().runOnMainSync(()->{assertNotNull(find(activity.findViewById(android.R.id.content),"Учебное избранное в Запорожье"));assertNotNull(find(activity.findViewById(android.R.id.content),"Избранное · все города и бюджеты"));});
        tap("Новые сначала");assertEquals("newest",db.state("ui",new JSONObject()).optString("sort"));
        assertTrue(UiDevice.getInstance(instrumentation()).takeScreenshot(new File(activity.getFilesDir(),"combined-favorites.png")));
        JSONObject report=db.state("combinedWorkspaceDeviceTest",new JSONObject());Json.put(report,"favoritesGlobal",true);Json.put(report,"visibleSort",true);db.saveState("combinedWorkspaceDeviceTest",report);
    }
    @Test public void trackingRetainsWatchCurrencyAndShowsExistingObject() {
        tap("Слежение");
        instrumentation().runOnMainSync(()->assertNotNull(find(activity.findViewById(android.R.id.content),"Учебное избранное в Запорожье")));
        assertTrue(UiDevice.getInstance(instrumentation()).takeScreenshot(new File(activity.getFilesDir(),"combined-tracking.png")));
        tap("Изменить порог");
        UiDevice device=UiDevice.getInstance(instrumentation());assertTrue(device.wait(Until.hasObject(By.text("USD")),5000));device.pressBack();
        JSONObject report=db.state("combinedWorkspaceDeviceTest",new JSONObject());Json.put(report,"trackingObjectVisible",true);Json.put(report,"savedUsdThresholdRetained",true);db.saveState("combinedWorkspaceDeviceTest",report);
    }
    @Test public void displayPriceEvidenceSurvivesSparseUpdates() {
        Context c=instrumentation().getTargetContext();String name="combined-price-evidence.db";c.deleteDatabase(name);
        try(Database fixture=new Database(c,name)) {
            JSONObject row=Json.object("url","https://example.org/display-price","source","training","title","Учебная квартира","city","Запоріжжя","kind","apartment","deal","sale","price",15000,"currency","USD","priceEvidence","display","observedAt",java.time.Instant.now().minusSeconds(2).toString());
            fixture.ingest(List.of(row),false);JSONObject next=Json.copy(row);next.remove("priceEvidence");Json.put(next,"price",14000);Json.put(next,"observedAt",Model.now());fixture.ingest(List.of(next),false);
            assertEquals("display",fixture.get(Model.identity(row)).optString("priceEvidence"));assertFalse(Model.verifiedPrice(fixture.get(Model.identity(row))));
            JSONObject api=Json.copy(next);Json.put(api,"source","domria");Json.put(api,"priceEvidence","native");Json.put(api,"observedAt",Model.now());fixture.ingest(List.of(api),false);assertTrue(Model.verifiedPrice(fixture.get(Model.identity(row))));
            JSONObject web=Json.copy(api);web.remove("priceEvidence");Json.put(web,"source","domria-web");Json.put(web,"observedAt",Model.now());fixture.ingest(List.of(web),false);assertEquals("display",fixture.get(Model.identity(row)).optString("priceEvidence"));
        }finally{c.deleteDatabase(name);}
    }
    @Test public void oldTrackingSwitchCannotOverwriteANewerThreshold() {
        tap("Слежение");
        java.util.concurrent.atomic.AtomicReference<Switch> control=new java.util.concurrent.atomic.AtomicReference<>();
        instrumentation().runOnMainSync(()->control.set((Switch)find(activity.findViewById(android.R.id.content),"Проверять объект")));
        assertNotNull(control.get());
        db.local(id,"watch",Json.object("enabled",false,"target",17000,"currency","UAH"));
        instrumentation().runOnMainSync(()->control.get().setChecked(true));
        JSONObject watch=db.get(id).optJSONObject("watch");assertEquals(17000,watch.optDouble("target"),0);assertEquals("UAH",watch.optString("currency"));assertTrue(watch.optBoolean("enabled"));
    }
    @Test public void selectedMapReturnPreservesSearchAndReopensOneCard() throws Exception {
        JSONObject before=Json.copy(db.state("currentFilters",new JSONObject()));
        java.lang.reflect.Method returned=MainActivity.class.getDeclaredMethod("onActivityResult",int.class,int.class,Intent.class);returned.setAccessible(true);
        instrumentation().runOnMainSync(()->{try{returned.invoke(activity,24,Activity.RESULT_OK,new Intent().putExtra("listingId",id).putExtra("selectedMap",true));}catch(Exception error){throw new AssertionError(error);}});
        instrumentation().waitForIdleSync();assertEquals(before.toString(),db.state("currentFilters",new JSONObject()).toString());
        UiDevice device=UiDevice.getInstance(instrumentation());assertTrue(device.wait(Until.hasObject(By.text("Карта этого объекта")),5000));
        assertTrue(device.takeScreenshot(new File(activity.getFilesDir(),"combined-details.png")));
        JSONObject report=db.state("combinedWorkspaceDeviceTest",new JSONObject());Json.put(report,"selectedReturnKeepsSearch",true);db.saveState("combinedWorkspaceDeviceTest",report);
    }
}

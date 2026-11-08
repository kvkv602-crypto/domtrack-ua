package ua.domtrack.mobile;

import java.util.*;
import org.json.*;

/** Presentation rules shared by the unified workspace and its device tests. */
final class Workspace {
    static List<Sources.Section> safeSourceSections(JSONObject filters) {
        if(filters==null)return List.of();
        String city=filters.optString("city");
        if(Arrays.stream(Sources.CITIES).noneMatch(row->Bilingual.city(row[0]).equals(Bilingual.city(city))))return List.of();
        return Sources.sections(city,filters.optString("kind","both"));
    }
    static JSONObject resultFilters(JSONObject current, boolean favorites) {
        JSONObject result=favorites?new JSONObject():Json.copy(current);
        if(favorites){Json.put(result,"favorite",true);Json.put(result,"currency",current.optString("currency","EUR"));}
        return result;
    }
    static int watchCurrencyIndex(JSONObject watch) {
        return watch==null?0:Math.max(0,List.of("EUR","USD","UAH").indexOf(watch.optString("currency","EUR")));
    }
    static String sort(JSONObject ui) {
        return "newest".equals(ui.optString("sort"))?"newest":"price";
    }
    static JSONObject savedSearch(JSONObject previous,String name,JSONObject filters) {
        JSONObject saved=previous==null?new JSONObject():Json.copy(previous);
        Json.put(saved,"name",name);Json.put(saved,"filters",Json.copy(filters));
        if(!saved.has("enabled"))Json.put(saved,"enabled",true);
        return saved;
    }
    static String coverage(List<JSONObject> sections) {
        Map<String,List<JSONObject>> sources=new LinkedHashMap<>();
        for(JSONObject section:sections)sources.computeIfAbsent(section.optString("source"),key->new ArrayList<>()).add(section);
        int complete=0,partial=0,failed=0,untested=0;
        for(List<JSONObject> states:sources.values()) {
            if(states.stream().anyMatch(s->!s.optString("error").isBlank()))failed++;
            else if(states.stream().allMatch(s->!s.optString("checkedAt").isBlank()&&s.optBoolean("complete")))complete++;
            else if(states.stream().anyMatch(s->!s.optString("checkedAt").isBlank()))partial++;
            else untested++;
        }
        return "Источники · завершены: "+complete+" · частично: "+partial+" · ошибки: "+failed+" · не проверены: "+untested;
    }
}

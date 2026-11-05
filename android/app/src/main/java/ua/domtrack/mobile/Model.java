package ua.domtrack.mobile;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.*;
import java.util.*;

final class Model {
    static final ZoneId KYIV=ZoneId.of("Europe/Kyiv");
    static String date() { return LocalDate.now(KYIV).toString(); }
    static String now() { return Instant.now().toString(); }
    static String normalized(String value) { return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim(); }
    static String hash(String text) { try { byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b));return s.toString(); }catch(Exception e){throw new IllegalStateException(e);} }
    static String identity(JSONObject r) { if(!r.optString("url").startsWith("https://")) throw new IllegalArgumentException("Нужна HTTPS-ссылка объявления"); return hash(r.optString("url").split("[?#]",2)[0]); }
    static void validate(JSONObject r) {
        identity(r);if(r.optString("title").isBlank()||r.optString("city").isBlank())throw new IllegalArgumentException("Укажите заголовок и город");
        if(!List.of("USD","EUR","UAH").contains(r.optString("currency")))throw new IllegalArgumentException("Валюта: USD, EUR или UAH");
        double price=r.optDouble("price",0);if(!Double.isFinite(price)||price<=0||price>1e12)throw new IllegalArgumentException("Нужна полная положительная цена");
        if(!List.of("apartment","house").contains(r.optString("kind"))||!r.optString("deal","sale").equals("sale"))throw new IllegalArgumentException("Поддерживается продажа целой квартиры или дома");
        if(r.toString().length()>64000)throw new IllegalArgumentException("Слишком большая запись");
    }
    static double convert(double value,String from,String to,JSONObject quote) { if(from.equals(to))return value;if(quote==null||!quote.optString("date").equals(date()))return Double.NaN;JSONObject rates=quote.optJSONObject("rates");double a=rates==null?Double.NaN:rates.optDouble(from,Double.NaN),b=rates==null?Double.NaN:rates.optDouble(to,Double.NaN);return a>0&&b>0?value*a/b:Double.NaN; }
    static boolean matches(JSONObject r,JSONObject f,JSONObject quote) {
        if(f.optBoolean("favorite")&&!r.optBoolean("favorite"))return false;
        String city=f.optString("city");if(!city.isBlank()&&!normalized(city).equals(normalized(r.optString("city"))))return false;
        String kind=f.optString("kind","both");if(!kind.equals("both")&&!kind.equals(r.optString("kind")))return false;
        String district=normalized(f.optString("district"));if(!district.isEmpty()&&!normalized(r.optString("district")+" "+r.optString("address")).contains(district))return false;
        String seller=f.optString("sellerType","any");if(!seller.equals("any")&&!seller.equals(r.optString("sellerType","unknown")))return false;
        for(String field:List.of("rooms","area","floor","floorsTotal"))for(String suffix:List.of("Min","Max"))if(f.has(field+suffix)&&!f.optString(field+suffix).isBlank()) { double v=r.optDouble(field,Double.NaN),bound=f.optDouble(field+suffix,Double.NaN);if(!Double.isFinite(v)||!Double.isFinite(bound)||(suffix.equals("Min")?v<bound:v>bound))return false; }
        if(f.has("priceMin")||f.has("priceMax")) { double price=convert(r.optDouble("price"),r.optString("currency"),f.optString("currency","EUR"),quote);if(!Double.isFinite(price))return false;if(f.has("priceMin")&&price<f.optDouble("priceMin"))return false;if(f.has("priceMax")&&price>f.optDouble("priceMax"))return false; }
        JSONArray polygon=f.optJSONArray("polygon");if(polygon!=null&&polygon.length()>=3&&!inside(r.optDouble("latitude",Double.NaN),r.optDouble("longitude",Double.NaN),polygon))return false;
        return true;
    }
    static boolean inside(double lat,double lon,JSONArray polygon) { if(!Double.isFinite(lat)||!Double.isFinite(lon))return false;boolean in=false;for(int i=0,j=polygon.length()-1;i<polygon.length();j=i++){JSONArray a=polygon.optJSONArray(i),b=polygon.optJSONArray(j);if(a==null||b==null)return false;double x=a.optDouble(1),y=a.optDouble(0),xx=b.optDouble(1),yy=b.optDouble(0);if((y>lat)!=(yy>lat)&&lon<(xx-x)*(lat-y)/(yy-y)+x)in=!in;}return in; }
    static String material(JSONObject r) { JSONObject o=new JSONObject();for(String key:List.of("title","address","district","rooms","area","floor","floorsTotal","sellerType","contactPhone","contactEmail","status","description"))Json.put(o,key,r.opt(key));return hash(o.toString()); }
    static List<String> eventTypes(JSONObject old,JSONObject next,boolean watched,JSONObject watch,JSONObject quote) {
        List<String> events=new ArrayList<>();if(old==null)return events;
        boolean price=old.optDouble("price")!=next.optDouble("price")||!old.optString("currency").equals(next.optString("currency"));
        if(price)events.add("price");if(watched&&!material(old).equals(material(next)))events.add("object");
        if(watch!=null&&watch.has("target")){double before=convert(old.optDouble("price"),old.optString("currency"),watch.optString("currency","EUR"),quote),after=convert(next.optDouble("price"),next.optString("currency"),watch.optString("currency","EUR"),quote),target=watch.optDouble("target");if(Double.isFinite(before)&&Double.isFinite(after)&&before>target&&after<=target)events.add("threshold");}
        return events;
    }
}

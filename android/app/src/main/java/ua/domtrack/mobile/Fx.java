package ua.domtrack.mobile;

import org.json.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

final class Fx {
    static JSONObject get(Database db,Network net,String date)throws Exception{LocalDate d=LocalDate.parse(date);if(d.isAfter(LocalDate.now(Model.KYIV))||d.isBefore(LocalDate.of(1996,9,2)))throw new IllegalArgumentException("Недопустимая дата курса");JSONObject old=db.state("fx:"+date,new JSONObject());if(old.has("rates"))return old;String body=net.get("https://bank.gov.ua/NBUStatService/v1/statdirectory/exchangenew?json&date="+date.replace("-","")).body;JSONArray rows=new JSONArray(body);JSONObject rates=Json.object("UAH",1);for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String cc=row.optString("cc");if(!cc.equals("USD")&&!cc.equals("EUR"))continue;String actual=LocalDate.parse(row.optString("exchangedate"),DateTimeFormatter.ofPattern("dd.MM.yyyy")).toString();double rate=row.optDouble("rate",0);if(!date.equals(actual)||rate<=0||!Double.isFinite(rate))throw new IllegalArgumentException("НБУ вернул курс за другую дату");Json.put(rates,cc,rate);}if(!rates.has("USD")||!rates.has("EUR"))throw new IllegalArgumentException("НБУ не вернул необходимые валюты");JSONObject quote=Json.object("date",date,"rates",rates,"source","НБУ","checkedAt",Model.now());db.state("fx:"+date,quote);return quote;}
}

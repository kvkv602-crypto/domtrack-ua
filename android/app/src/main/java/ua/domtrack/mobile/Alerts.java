package ua.domtrack.mobile;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.os.Build;
import java.time.*;
import java.util.*;
import org.json.*;

final class Alerts {
    static final String[] TYPES={"new","price","object","threshold"};
    static final String[] TITLES={"Новые объявления","Любое изменение цены","Изменения выбранных объектов","Порог цены"};
    static void channels(Context c){NotificationManager m=c.getSystemService(NotificationManager.class);for(int i=0;i<TYPES.length;i++){NotificationChannel ch=new NotificationChannel("domtrack_"+TYPES[i],TITLES[i],NotificationManager.IMPORTANCE_HIGH);ch.setDescription("События сохранённых поисков DomTrack UA");ch.enableVibration(true);m.createNotificationChannel(ch);}NotificationChannel silent=new NotificationChannel("domtrack_quiet","Тихие уведомления",NotificationManager.IMPORTANCE_LOW);silent.setSound(null,null);silent.enableVibration(false);m.createNotificationChannel(silent);}
    static void deliver(Context c,Database db){channels(c);if(Build.VERSION.SDK_INT>=33&&c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;JSONObject settings=db.state("settings",new JSONObject());int hour=LocalTime.now().getHour(),start=settings.optInt("quietStart",23),end=settings.optInt("quietEnd",8);boolean quiet=settings.optBoolean("quiet",true)&&(start>end?(hour>=start||hour<end):(hour>=start&&hour<end));List<JSONObject> events=db.events(true);Collections.reverse(events);int sounding=0;for(JSONObject e:events){String type=e.optString("type");if(!settings.optBoolean("alert_"+type,true)){db.delivered(e.optLong("eventId"));continue;}Intent intent=new Intent(c,MainActivity.class).putExtra("listingId",e.optString("listingId")).setAction("event:"+e.optLong("eventId")).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);PendingIntent pi=PendingIntent.getActivity(c,(int)e.optLong("eventId"),intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);String channel=quiet||sounding>=3?"domtrack_quiet":"domtrack_"+type;Notification n=new Notification.Builder(c,channel).setSmallIcon(R.drawable.ic_domtrack).setContentTitle(e.optString("title")).setContentText(e.optString("message")).setStyle(new Notification.BigTextStyle().bigText(e.optString("message"))).setContentIntent(pi).setAutoCancel(true).setGroup("domtrack_changes").setOnlyAlertOnce(true).build();try{c.getSystemService(NotificationManager.class).notify((int)e.optLong("eventId"),n);db.delivered(e.optLong("eventId"));sounding++;}catch(SecurityException ignored){return;}}}
}

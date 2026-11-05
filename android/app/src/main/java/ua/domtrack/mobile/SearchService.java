package ua.domtrack.mobile;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Owns explicit searches independently of the Activity and its screen lifecycle. */
public class SearchService extends Service {

    static final int NOTIFICATION = 730;
    static final String CHANNEL = "full-search",
        STOP = "ua.domtrack.STOP_SEARCH";
    static final AtomicBoolean RUNNING = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wakeLock;
    private Database db;
    private volatile Scanner scanner;
    private long lastNotification;

    public void onCreate() {
        super.onCreate();
        db = ((DomTrackApp) getApplication()).db();
        NotificationChannel channel = new NotificationChannel(
            CHANNEL,
            "Полный поиск",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    static void start(Context context, JSONObject filters) {
        context.startForegroundService(
            new Intent(context, SearchService.class).putExtra("filters", filters.toString())
        );
    }

    static void coordinates(Context context, JSONObject filters) {
        context.startForegroundService(
            new Intent(context, SearchService.class)
                .putExtra("filters", filters.toString())
                .putExtra("mode", "coordinates")
        );
    }

    static void stop(Context context) {
        context.startService(new Intent(context, SearchService.class).setAction(STOP));
    }

    public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stopped.set(true);
            if (scanner != null) scanner.cancel();
            if (!RUNNING.get()) stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(
            NOTIFICATION,
            notification("Подготовка поиска…", true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        );
        if (!RUNNING.compareAndSet(false, true)) return START_NOT_STICKY;
        JSONObject pending = db.state("activeSearch", new JSONObject());
        String requested = intent == null
            ? pending.optString("filters")
            : intent.getStringExtra("filters");
        if (requested == null || requested.isBlank()) {
            RUNNING.set(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        JSONObject filters = Json.obj(requested);
        String mode = intent == null
            ? pending.optString("mode", "search")
            : intent.getStringExtra("mode");
        boolean coordinates = "coordinates".equals(mode);
        stopped.set(false);
        db.saveState(
            "activeSearch",
            Json.object(
                "running",
                true,
                "filters",
                requested,
                "mode",
                mode,
                "message",
                "Подготовка поиска…"
            )
        );
        // Keep only the CPU awake during the explicit operation; the screen stays off.
        wakeLock = getSystemService(PowerManager.class).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DomTrack:FullSearch"
        );
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(TimeUnit.HOURS.toMillis(5));
        new Handler(Looper.getMainLooper()).postDelayed(
            () -> {
                if (RUNNING.get()) {
                    stopped.set(true);
                    if (scanner != null) scanner.cancel();
                }
            },
            TimeUnit.HOURS.toMillis(5) - 60000
        );
        executor.execute(() -> {
            String message;
            try {
                while (Scanner.BUSY.get() && !stopped.get()) Thread.sleep(250);
                scanner = new Scanner(this, db);
                int n = stopped.get()
                    ? 0
                    : coordinates
                        ? scanner.coordinates(filters, stopped::get, this::progress)
                        : scanner.scan(true, stopped::get, this::progress, filters);
                if (
                    !coordinates &&
                    !stopped.get() &&
                    db.state("settings", new JSONObject()).optBoolean("localAi", true)
                ) {
                    progress("Обход страниц закончен · анализ описаний ИИ на телефоне…");
                    try {
                        SemanticIndex.build(this, db, stopped::get);
                    } catch (Exception | LinkageError e) {
                        db.saveState(
                            "aiStatus",
                            Json.object("message", "Не удалось закончить анализ ИИ")
                        );
                    }
                }
                JSONObject last = db.state("lastCheck", new JSONObject());
                message = stopped.get()
                    ? "Поиск остановлен · продолжение сохранено"
                    : coordinates
                        ? "Загрузка координат завершена · найдено: " + n
                        : last.optBoolean("complete")
                            ? "Поиск завершён · обработано записей: " + n
                            : "Обход приостановлен · проверь состояние источников";
            } catch (Exception e) {
                message = stopped.get()
                    ? "Поиск остановлен · продолжение сохранено"
                    : "Поиск прерван: " + Scanner.safe(e);
            } finally {
                RUNNING.set(false);
                releaseWakeLock();
            }
            db.saveState(
                "activeSearch",
                Json.object(
                    "running",
                    false,
                    "filters",
                    requested,
                    "message",
                    message,
                    "at",
                    Model.now()
                )
            );
            try {
                getSystemService(NotificationManager.class).notify(
                    NOTIFICATION,
                    notification(message, false)
                );
            } catch (SecurityException ignored) {}
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        });
        return START_REDELIVER_INTENT;
    }

    private void progress(String message) {
        JSONObject state = db.state("activeSearch", new JSONObject());
        Json.put(state, "message", message);
        Json.put(state, "at", Model.now());
        db.saveState("activeSearch", state);
        if (System.currentTimeMillis() - lastNotification > 1500) {
            lastNotification = System.currentTimeMillis();
            try {
                getSystemService(NotificationManager.class).notify(
                    NOTIFICATION,
                    notification(message, true)
                );
            } catch (SecurityException ignored) {}
        }
    }

    private Notification notification(String message, boolean ongoing) {
        PendingIntent open = PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_domtrack)
            .setContentTitle("DomTrack UA · поиск")
            .setContentText(message)
            .setStyle(new Notification.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true);
        if (ongoing) b.addAction(
            new Notification.Action.Builder(
                null,
                "Остановить",
                PendingIntent.getService(
                    this,
                    1,
                    new Intent(this, SearchService.class).setAction(STOP),
                    PendingIntent.FLAG_IMMUTABLE
                )
            ).build()
        );
        return b.build();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    public void onTimeout(int startId, int type) {
        stopped.set(true);
        if (scanner != null) scanner.cancel();
        db.saveState(
            "activeSearch",
            Json.object(
                "running",
                false,
                "message",
                "Android приостановил поиск · очередь сохранена"
            )
        );
        releaseWakeLock();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    public void onDestroy() {
        stopped.set(true);
        if (scanner != null) scanner.cancel();
        executor.shutdownNow();
        releaseWakeLock();
        super.onDestroy();
    }

    public IBinder onBind(Intent intent) {
        return null;
    }
}

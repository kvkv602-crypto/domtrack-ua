package ua.domtrack.mobile;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;
import org.json.*;

final class Database extends SQLiteOpenHelper {

    Database(Context c) {
        this(c, "domtrack.db");
    }

    Database(Context c, String name) {
        super(c, name, null, 2);
        setWriteAheadLoggingEnabled(true);
    }

    public void onCreate(SQLiteDatabase db) {
        embeddingsTable(db);
        db.execSQL(
            "CREATE TABLE listing (id TEXT PRIMARY KEY, data TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 1)"
        );
        db.execSQL(
            "CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, listing TEXT NOT NULL, observed TEXT NOT NULL, data TEXT NOT NULL)"
        );
        db.execSQL("CREATE INDEX history_listing ON history(listing, id)");
        db.execSQL("CREATE TABLE state (name TEXT PRIMARY KEY, data TEXT NOT NULL)");
        db.execSQL(
            "CREATE TABLE event (id INTEGER PRIMARY KEY AUTOINCREMENT, eventkey TEXT UNIQUE NOT NULL, data TEXT NOT NULL, delivered INTEGER NOT NULL DEFAULT 0, seen INTEGER NOT NULL DEFAULT 0)"
        );
    }

    public void onUpgrade(SQLiteDatabase db, int old, int next) {
        if (old < 2) embeddingsTable(db);
    }

    private void embeddingsTable(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS embedding (key TEXT PRIMARY KEY, vector BLOB NOT NULL)"
        );
    }

    synchronized float[] embedding(String key) {
        try (
            Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT vector FROM embedding WHERE key=?",
                new String[] { key }
            )
        ) {
            if (!cursor.moveToFirst()) return null;
            byte[] bytes = cursor.getBlob(0);
            if (bytes.length != 384 * 4) return null;
            java.nio.FloatBuffer buffer = java.nio.ByteBuffer.wrap(bytes)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer();
            float[] out = new float[384];
            buffer.get(out);
            return out;
        }
    }

    synchronized void saveEmbedding(String key, float[] vector) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(vector.length * 4).order(
            java.nio.ByteOrder.LITTLE_ENDIAN
        );
        for (float f : vector) buffer.putFloat(f);
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("vector", buffer.array());
        getWritableDatabase().insertWithOnConflict(
            "embedding",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        );
    }

    synchronized JSONObject state(String name, JSONObject fallback) {
        try (
            Cursor c = getReadableDatabase().rawQuery(
                "SELECT data FROM state WHERE name=?",
                new String[] { name }
            )
        ) {
            return c.moveToFirst() ? Json.obj(c.getString(0)) : Json.copy(fallback);
        }
    }

    synchronized void saveState(String name, JSONObject value) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("data", value.toString());
        getWritableDatabase().insertWithOnConflict(
            "state",
            null,
            v,
            SQLiteDatabase.CONFLICT_REPLACE
        );
    }

    synchronized JSONObject get(String id) {
        try (
            Cursor c = getReadableDatabase().rawQuery(
                "SELECT data FROM listing WHERE id=?",
                new String[] { id }
            )
        ) {
            return c.moveToFirst() ? Json.obj(c.getString(0)) : null;
        }
    }

    synchronized List<JSONObject> listings() {
        List<JSONObject> rows = new ArrayList<>();
        try (
            Cursor c = getReadableDatabase().rawQuery(
                "SELECT data FROM listing ORDER BY rowid DESC",
                null
            )
        ) {
            while (c.moveToNext()) rows.add(Json.obj(c.getString(0)));
        }
        return rows;
    }

    synchronized List<JSONObject> history(String id) {
        List<JSONObject> rows = new ArrayList<>();
        try (
            Cursor c = getReadableDatabase().rawQuery(
                "SELECT data FROM history WHERE listing=? ORDER BY id DESC LIMIT 500",
                new String[] { id }
            )
        ) {
            while (c.moveToNext()) rows.add(Json.obj(c.getString(0)));
        }
        return rows;
    }

    synchronized List<JSONObject> searches() {
        return Json.objects(
            state("searches", Json.object("items", new JSONArray())).optJSONArray("items")
        );
    }

    synchronized void saveSearch(JSONObject search) {
        List<JSONObject> rows = searches();
        String id = search.optString("id", UUID.randomUUID().toString());
        Json.put(search, "id", id);
        rows.removeIf(r -> r.optString("id").equals(id));
        rows.add(search);
        saveState("searches", Json.object("items", Json.array(rows)));
    }

    synchronized void deleteSearch(String id) {
        List<JSONObject> rows = searches();
        rows.removeIf(r -> r.optString("id").equals(id));
        saveState("searches", Json.object("items", Json.array(rows)));
    }

    synchronized void local(String id, String name, Object value) {
        JSONObject row = get(id);
        if (row == null) return;
        Json.put(row, name, value);
        Json.put(row, "localUpdatedAt", Model.now());
        ContentValues v = new ContentValues();
        v.put("data", row.toString());
        getWritableDatabase().update("listing", v, "id=?", new String[] { id });
    }

    synchronized void location(String id, JSONObject geo) {
        JSONObject row = get(id);
        if (row == null) return;
        for (String key : List.of("latitude", "longitude", "locationAccuracy"))
            if (geo.has(key)) Json.put(row, key, geo.opt(key));
        ContentValues values = new ContentValues();
        values.put("data", row.toString());
        getWritableDatabase().update("listing", values, "id=?", new String[] { id });
    }

    synchronized void locationAttempt(String id) {
        JSONObject row = get(id);
        if (row == null) return;
        Json.put(row, "coordinatesCheckedAt", System.currentTimeMillis());
        ContentValues values = new ContentValues();
        values.put("data", row.toString());
        getWritableDatabase().update("listing", values, "id=?", new String[] { id });
    }

    synchronized int ingest(List<JSONObject> rows, boolean notify) {
        return ingest(rows, notify, notify);
    }

    synchronized int ingest(List<JSONObject> rows, boolean notifyNew, boolean notifyExisting) {
        SQLiteDatabase db = getWritableDatabase();
        int count = 0;
        List<JSONObject> searches = searches();
        JSONObject quote = state("fx:" + Model.date(), new JSONObject());
        db.beginTransaction();
        try {
            for (JSONObject input : rows) {
                try {
                    Model.validate(input);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                JSONObject r = Json.copy(input);
                String id = Model.identity(r);
                JSONObject old = get(id);
                String observed = r.optString("observedAt", Model.now());
                java.time.Instant at;
                try {
                    at = java.time.Instant.parse(observed);
                    if (at.isAfter(java.time.Instant.now().plusSeconds(300))) continue;
                } catch (Exception e) {
                    continue;
                }
                if (
                    old != null && java.time.Instant.parse(old.optString("lastSeen")).isAfter(at)
                ) continue;
                Json.put(r, "id", id);
                Json.put(r, "lastSeen", observed);
                Json.put(
                    r,
                    "firstSeen",
                    old == null ? observed : old.optString("firstSeen", observed)
                );
                Json.put(r, "revision", old == null ? 1 : old.optInt("revision", 1) + 1);
                if (old != null) for (String key : List.of(
                    "favorite",
                    "note",
                    "watch",
                    "visited",
                    "hidden",
                    "localUpdatedAt",
                    "sameGroup",
                    "differentGroup",
                    "photos",
                    "checklist"
                ))
                    if (old.has(key)) Json.put(r, key, old.opt(key));
                if (old != null) for (String extra : List.of(
                    "structured",
                    "priceEvidence",
                    "catalogUrl",
                    "catalogPage",
                    "latitude",
                    "longitude",
                    "locationAccuracy",
                    "coordinatesCheckedAt"
                ))
                    if (!r.has(extra) && old.has(extra)) Json.put(r, extra, old.opt(extra));
                Model.keepKnownFacts(old,r);
                boolean changed =
                    old == null ||
                    old.optDouble("price") != r.optDouble("price") ||
                    !old.optString("currency").equals(r.optString("currency")) ||
                    !Model.material(old).equals(Model.material(r));
                if (changed) {
                    ContentValues h = new ContentValues();
                    h.put("listing", id);
                    h.put("observed", observed);
                    h.put("data", r.toString());
                    db.insertOrThrow("history", null, h);
                }
                if (old == null ? notifyNew : notifyExisting) {
                    boolean inSearch = false;
                    for (JSONObject s : searches) {
                        JSONObject filters = s.optJSONObject("filters");
                        if (
                            s.optBoolean("enabled", true) &&
                            filters != null &&
                            (Model.matches(r, filters, quote) ||
                                (old != null && Model.matches(old, filters, quote)))
                        ) {
                            inSearch = true;
                            break;
                        }
                    }
                    JSONObject watch = r.optJSONObject("watch");
                    boolean watched = watch != null && watch.optBoolean("enabled", true);
                    if (old == null && inSearch) event(
                        "new",
                        r,
                        "Новое объявление",
                        r.optString("title"),
                        observed
                    );
                    for (String type : Model.eventTypes(old, r, watched, watch, quote))
                        if (inSearch || watched) {
                            String text = type.equals("price")
                                ? "Цена: " +
                                old.optDouble("price") +
                                " " +
                                old.optString("currency") +
                                " → " +
                                r.optDouble("price") +
                                " " +
                                r.optString("currency")
                                : type.equals("threshold")
                                    ? "Достигнут выбранный порог цены"
                                    : "Изменились характеристики объявления";
                            event(type, r, text, r.optString("title"), observed);
                        }
                }
                ContentValues v = new ContentValues();
                v.put("id", id);
                v.put("data", r.toString());
                v.put("revision", r.optInt("revision"));
                db.insertWithOnConflict("listing", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                count++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return count;
    }

    private void event(String type, JSONObject r, String title, String message, String observed) {
        ContentValues e = new ContentValues();
        e.put(
            "eventkey",
            Model.hash(
                type +
                    ":" +
                    r.optString("id") +
                    ":" +
                    observed +
                    ":" +
                    r.optDouble("price") +
                    ":" +
                    Model.material(r)
            )
        );
        e.put(
            "data",
            Json.object(
                "type",
                type,
                "listingId",
                r.optString("id"),
                "title",
                title,
                "message",
                message,
                "createdAt",
                Model.now()
            ).toString()
        );
        getWritableDatabase().insertWithOnConflict(
            "event",
            null,
            e,
            SQLiteDatabase.CONFLICT_IGNORE
        );
    }

    synchronized List<JSONObject> events(boolean pending) {
        List<JSONObject> rows = new ArrayList<>();
        try (
            Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,data FROM event " +
                    (pending ? "WHERE delivered=0 " : "") +
                    "ORDER BY id DESC LIMIT 200",
                null
            )
        ) {
            while (c.moveToNext()) {
                JSONObject r = Json.obj(c.getString(1));
                Json.put(r, "eventId", c.getLong(0));
                rows.add(r);
            }
        }
        return rows;
    }

    synchronized void delivered(long id) {
        ContentValues v = new ContentValues();
        v.put("delivered", 1);
        getWritableDatabase().update("event", v, "id=?", new String[] { String.valueOf(id) });
    }

    synchronized JSONObject exportData() {
        JSONObject attachments = new JSONObject();
        for (JSONObject row : listings()) {
            String id = row.optString("id");
            JSONObject photos = state("photos:" + id, new JSONObject());
            if (photos.has("items")) Json.put(attachments, id, photos);
        }
        JSONArray records = new JSONArray();
        for (JSONObject r : listings())
            for (JSONObject h : history(r.optString("id"))) records.put(h);
        return Json.object(
            "format",
            "domtrack-mobile",
            "version",
            1,
            "exportedAt",
            Model.now(),
            "records",
            records,
            "local",
            Json.array(listings()),
            "searches",
            Json.array(searches()),
            "attachments",
            attachments
        );
    }
}

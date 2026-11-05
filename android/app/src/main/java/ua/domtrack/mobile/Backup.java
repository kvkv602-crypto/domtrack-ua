package ua.domtrack.mobile;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.json.*;

final class Backup {

    static byte[] encrypt(JSONObject data, char[] password) throws Exception {
        if (password.length < 10) throw new IllegalArgumentException(
            "Пароль резервной копии: минимум 10 символов"
        );
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt));
        byte[] bytes = cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8));
        return Json.object(
            "format",
            "domtrack-encrypted",
            "version",
            1,
            "iterations",
            210000,
            "salt",
            Base64.getEncoder().encodeToString(salt),
            "iv",
            Base64.getEncoder().encodeToString(cipher.getIV()),
            "ciphertext",
            Base64.getEncoder().encodeToString(bytes)
        )
            .toString()
            .getBytes(StandardCharsets.UTF_8);
    }

    static JSONObject decrypt(String text, char[] password) throws Exception {
        JSONObject data = Json.obj(text);
        if (
            !data.optString("format").equals("domtrack-encrypted") ||
            data.optInt("version") != 1 ||
            data.optInt("iterations") != 210000
        ) throw new IllegalArgumentException("Неподдерживаемый формат резервной копии");
        byte[] salt = Base64.getDecoder().decode(data.getString("salt")),
            iv = Base64.getDecoder().decode(data.getString("iv"));
        if (salt.length != 16 || iv.length != 12) throw new IllegalArgumentException(
            "Повреждённая резервная копия"
        );
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), new GCMParameterSpec(128, iv));
        return Json.obj(
            new String(
                cipher.doFinal(Base64.getDecoder().decode(data.getString("ciphertext"))),
                StandardCharsets.UTF_8
            )
        );
    }

    private static SecretKeySpec key(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, 210000, 256);
        try {
            return new SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded(),
                "AES"
            );
        } finally {
            spec.clearPassword();
        }
    }

    static int restore(Database db, JSONObject data) {
        JSONArray array = data.optJSONArray("records");
        if (array == null) throw new IllegalArgumentException("В файле нет массива records");
        ListOrder sorted = new ListOrder(Json.objects(array));
        int n = db.ingest(sorted.rows, false);
        for (JSONObject r : Json.objects(data.optJSONArray("local"))) {
            String id = Model.identity(r);
            JSONObject current = db.get(id);
            if (current == null) continue;
            for (String k : java.util.List.of(
                "favorite",
                "note",
                "watch",
                "visited",
                "hidden",
                "sameGroup"
            ))
                if (r.has(k)) db.local(id, k, r.opt(k));
        }
        for (JSONObject s : Json.objects(data.optJSONArray("searches"))) db.saveSearch(s);
        JSONObject attachments = data.optJSONObject("attachments");
        if (attachments != null) for (String id : attachments.keySet())
            if (id.matches("[a-f0-9]{64}") && db.get(id) != null) {
                JSONObject photo = attachments.optJSONObject(id);
                if (
                    photo != null &&
                    photo.optJSONArray("items") != null &&
                    photo.optJSONArray("items").length() <= 3 &&
                    photo.toString().length() < 1300000
                ) db.state("photos:" + id, photo);
            }
        return n;
    }

    private static class ListOrder {

        final java.util.List<JSONObject> rows;

        ListOrder(java.util.List<JSONObject> r) {
            rows = r;
            rows.sort(java.util.Comparator.comparing(o -> o.optString("observedAt")));
        }
    }
}

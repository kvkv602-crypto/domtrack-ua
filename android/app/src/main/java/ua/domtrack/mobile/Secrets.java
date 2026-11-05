package ua.domtrack.mobile;

import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

final class Secrets {

    private static final String ALIAS = "domtrack-ria-v1";
    private final Context context;

    Secrets(Context c) {
        context = c.getApplicationContext();
    }

    private javax.crypto.SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator g = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            );
            g.init(
                new KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            );
            g.generateKey();
        }
        return (javax.crypto.SecretKey) ks.getKey(ALIAS, null);
    }

    void set(String value) throws Exception {
        if (value.isBlank()) {
            context.getSharedPreferences("secrets", 0).edit().clear().commit();
            return;
        }
        if (!value.matches("[A-Za-z0-9_.-]{10,200}")) throw new IllegalArgumentException(
            "Проверьте формат API-ключа"
        );
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        String data =
            Base64.encodeToString(c.getIV(), Base64.NO_WRAP) +
            ":" +
            Base64.encodeToString(
                c.doFinal(value.getBytes(StandardCharsets.UTF_8)),
                Base64.NO_WRAP
            );
        if (
            !context.getSharedPreferences("secrets", 0).edit().putString("ria", data).commit()
        ) throw new IllegalStateException("Ключ не сохранён");
    }

    String get() throws Exception {
        String s = context.getSharedPreferences("secrets", 0).getString("ria", "");
        if (s.isEmpty()) return "";
        String[] p = s.split(":", 2);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(
            Cipher.DECRYPT_MODE,
            key(),
            new GCMParameterSpec(128, Base64.decode(p[0], Base64.NO_WRAP))
        );
        return new String(c.doFinal(Base64.decode(p[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    boolean configured() {
        return context.getSharedPreferences("secrets", 0).contains("ria");
    }
}

package ua.domtrack.mobile;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.util.Base64;
import java.io.*;

final class Photos {

    static String selected(Context c, Uri uri) throws IOException {
        byte[] data;
        try (InputStream input = c.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Фотография недоступна");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] part = new byte[8192];
            int n;
            while ((n = input.read(part)) != -1) {
                if (buffer.size() + n > 20 * 1024 * 1024) throw new IOException(
                    "Выбери фотографию до 20 МБ"
                );
                buffer.write(part, 0, n);
            }
            data = buffer.toByteArray();
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException(
            "Файл не является поддерживаемым изображением"
        );
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (
            Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1024
        ) options.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (bitmap == null) throw new IOException("Не удалось прочитать фотографию");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, output);
        bitmap.recycle();
        if (output.size() > 300000) throw new IOException(
            "Фотография слишком подробная; выбери изображение меньшего размера"
        );
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
    }

    static Bitmap bitmap(String data) {
        try {
            byte[] bytes = Base64.decode(data, Base64.NO_WRAP);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }
}

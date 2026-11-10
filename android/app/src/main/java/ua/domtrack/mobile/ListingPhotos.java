package ua.domtrack.mobile;

import java.util.*;
import java.net.URI;
import java.io.*;
import org.json.*;
import org.jsoup.nodes.Element;

/** Public source-photo metadata is separate from personal photo attachments. */
final class ListingPhotos {
    static final int LIMIT = 12;
    static final int MAX_BYTES = 3 * 1024 * 1024, MAX_SIDE = 1280;

    static byte[] read(InputStream input, long declaredLength) throws IOException {
        if (declaredLength > MAX_BYTES) throw new IOException("Фото превышает лимит размера");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int count;
        while ((count = input.read(chunk)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Загрузка фото остановлена");
            if (bytes.size() + count > MAX_BYTES) throw new IOException("Фото превышает лимит размера");
            bytes.write(chunk, 0, count);
        }
        return bytes.toByteArray();
    }

    static int sampleSize(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || (long) width * height > 120000000L)
            throw new IOException("Формат или размер фото не поддерживается");
        int sample = 1;
        while ((Math.max(width, height) + sample - 1) / sample > MAX_SIDE) sample *= 2;
        return sample;
    }

    static String safeUrl(String value, String base) {
        try {
            if (value == null || value.isBlank() || value.length() > 2048 ||
                    value.matches("(?s).*[\\x00-\\x20\\\\].*")) return "";
            URI uri = URI.create(value);
            if (!uri.isAbsolute()) {
                // A photo identifier is not a URL and must never be turned into a guessed CDN path.
                if (!value.startsWith("/") || base == null || base.isBlank()) return "";
                uri = URI.create(base).resolve(uri);
            }
            String host = uri.getHost();
            if (host == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null ||
                    (uri.getPort() != -1 && uri.getPort() != 443) || uri.getRawFragment() != null) return "";
            host = host.toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
            // Source CDNs use DNS names. Runtime DNS checks also reject private/reserved addresses.
            if (!host.contains(".") || host.contains(":") || host.matches("[0-9.]+") ||
                    host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local") ||
                    host.endsWith(".internal") || host.endsWith(".lan") || host.endsWith(".home")) return "";
            return uri.toASCIIString();
        } catch (IllegalArgumentException ignored) { return ""; }
    }

    static List<String> urls(JSONObject row) {
        if (row == null) return List.of();
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        collect(row.opt("image"), "", urls, 0);
        collect(row.opt("remotePhotos"), "", urls, 0);
        return List.copyOf(urls);
    }

    static void metadata(JSONObject row, Object raw, String base) {
        LinkedHashSet<String> urls = new LinkedHashSet<>(urls(row));
        collect(raw, base, urls, 0);
        if (!urls.isEmpty()) {
            Json.put(row, "image", urls.iterator().next());
            Json.put(row, "remotePhotos", new JSONArray(urls));
        }
    }

    private static void collect(Object raw, String base, Set<String> urls, int depth) {
        if (raw == null || depth > 3 || urls.size() >= LIMIT) return;
        if (raw instanceof String value) {
            String safe = safeUrl(value, base);
            if (!safe.isEmpty()) urls.add(safe);
        } else if (raw instanceof JSONArray array) {
            for (int i = 0; i < Math.min(48, array.length()) && urls.size() < LIMIT; i++)
                collect(array.opt(i), base, urls, depth + 1);
        } else if (raw instanceof JSONObject object) {
            collect(object.has("contentUrl") ? object.opt("contentUrl") : object.opt("url"), base, urls, depth + 1);
        }
    }

    static void card(Element card, JSONObject row) {
        for (Element image : card.select("img[src],img[data-src],img[data-lazy-src]")) {
            if (!Sources.visible(image)) continue;
            String url = imageUrl(image);
            if (!url.isEmpty()) { metadata(row, url, ""); return; }
        }
    }

    static void olxGallery(Element page, JSONObject row) {
        for (Element image : page.select("[data-testid=photo-gallery] img," +
                "[data-testid=ad-photo] img,img[data-testid=ad-photo]")) {
            if (image.closest("[data-testid=recommended-ads],[data-cy=l-card],[data-testid=l-card]") != null) continue;
            if (Sources.visible(image)) metadata(row, imageUrl(image), "");
            if (urls(row).size() >= LIMIT) break;
        }
    }

    private static String imageUrl(Element image) {
        for (String field : List.of("data-src", "data-lazy-src", "src")) {
            String url = safeUrl(image.absUrl(field), "");
            if (!url.isEmpty()) return url;
        }
        return "";
    }
}

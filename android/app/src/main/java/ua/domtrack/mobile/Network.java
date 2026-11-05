package ua.domtrack.mobile;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

final class Network {

    static final int MAX_BYTES = 4 * 1024 * 1024;
    static final String AGENT = "DomTrackUA/0.2 Android (personal property monitor)";

    static boolean sameCatalogSite(String a, String b) {
        if (a == null || b == null) return false;
        return (
            a.equalsIgnoreCase(b) ||
            (List.of("www.olx.ua", "m.olx.ua").contains(a.toLowerCase(Locale.ROOT)) &&
                List.of("www.olx.ua", "m.olx.ua").contains(b.toLowerCase(Locale.ROOT)))
        );
    }

    static final class HttpFailure extends IOException {

        final int status;

        HttpFailure(int code) {
            super("Сайт ответил HTTP " + code);
            status = code;
        }
    }

    static boolean robotsUnavailable(int status) {
        // RFC 9309 §2.3.1.3 permits public-resource requests when robots.txt returns 4xx.
        // A rate limit still pauses requests; server and network failures remain failures.
        return status >= 400 && status < 500 && status != 429;
    }

    private final OkHttpClient client = new OkHttpClient.Builder()
        .dns(host -> {
            List<InetAddress> addresses = Arrays.asList(InetAddress.getAllByName(host));
            for (InetAddress address : addresses)
                if (!isPublic(address)) throw new UnknownHostException(
                    "Доступен только публичный интернет"
                );
            return addresses;
        })
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build();

    static boolean isPublic(InetAddress a) {
        if (
            a.isAnyLocalAddress() ||
            a.isLoopbackAddress() ||
            a.isLinkLocalAddress() ||
            a.isSiteLocalAddress() ||
            a.isMulticastAddress()
        ) return false;
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int x = b[0] & 255,
                y = b[1] & 255;
            return !(
                x == 0 ||
                x == 10 ||
                x == 127 ||
                x >= 224 ||
                (x == 100 && y >= 64 && y <= 127) ||
                (x == 169 && y == 254) ||
                (x == 172 && y >= 16 && y <= 31) ||
                (x == 192 && (y == 0 || y == 168)) ||
                (x == 198 && (y == 18 || y == 19))
            );
        }
        return (
            (b[0] & 0xe0) == 0x20 &&
            !(b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == (byte) 0xb8)
        );
    }

    static HttpUrl safe(String value) {
        HttpUrl u = HttpUrl.parse(value);
        if (
            u == null ||
            !u.isHttps() ||
            !u.username().isEmpty() ||
            !u.password().isEmpty() ||
            u.port() != 443 ||
            u.host().endsWith(".local") ||
            value.length() > 2048
        ) throw new IllegalArgumentException("Нужна публичная HTTPS-ссылка без пароля");
        return u;
    }

    static final class Page {

        final String body, url, etag, modified;
        final int status;

        Page(String b, String u, int s, String e, String m) {
            body = b;
            url = u;
            status = s;
            etag = e == null ? "" : e;
            modified = m == null ? "" : m;
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Object> HOST_LOCKS =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> NEXT_REQUEST =
        new java.util.concurrent.ConcurrentHashMap<>();

    void cancel() {
        client.dispatcher().cancelAll();
    }

    Page get(String value, String etag, String modified) throws IOException {
        String host = safe(value).host();
        synchronized (HOST_LOCKS.computeIfAbsent(host, k -> new Object())) {
            long delay = NEXT_REQUEST.getOrDefault(host, 0L) - System.currentTimeMillis();
            if (delay > 0) try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Загрузка остановлена");
            }
            try {
                return fetch(value, etag, modified);
            } finally {
                NEXT_REQUEST.put(host, System.currentTimeMillis() + 2000);
            }
        }
    }

    private Page fetch(String value, String etag, String modified) throws IOException {
        HttpUrl url = safe(value);
        for (int redirect = 0; redirect < 4; redirect++) {
            Request.Builder b = new Request.Builder()
                .url(url)
                .header("User-Agent", AGENT)
                .header("Accept", "text/html,application/json;q=0.9");
            if (etag != null && !etag.isEmpty()) b.header("If-None-Match", etag);
            if (modified != null && !modified.isEmpty()) b.header("If-Modified-Since", modified);
            try (Response r = client.newCall(b.build()).execute()) {
                if (r.code() == 304) return new Page("", url.toString(), 304, etag, modified);
                if (r.isRedirect()) {
                    HttpUrl next = url.resolve(Objects.toString(r.header("Location"), ""));
                    if (next == null || !next.host().equals(url.host())) throw new IOException(
                        "Переадресация на другой сайт не выполнена"
                    );
                    url = safe(next.toString());
                    continue;
                }
                if (!r.isSuccessful()) throw new HttpFailure(r.code());
                if (r.body() == null) throw new IOException("Пустой ответ сайта");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = r.body().byteStream()) {
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = in.read(chunk)) != -1) {
                        if (out.size() + n > MAX_BYTES) throw new IOException(
                            "Страница превышает лимит 4 МБ"
                        );
                        out.write(chunk, 0, n);
                    }
                }
                return new Page(
                    out.toString(StandardCharsets.UTF_8),
                    url.toString(),
                    r.code(),
                    r.header("ETag"),
                    r.header("Last-Modified")
                );
            }
        }
        throw new IOException("Слишком много перенаправлений");
    }

    Page get(String url) throws IOException {
        return get(url, null, null);
    }

    boolean robotsAllowed(String url, Database db) throws IOException {
        HttpUrl u = safe(url);
        String root = u.scheme() + "://" + u.host();
        org.json.JSONObject cached = db.state("robots:" + root, new org.json.JSONObject());
        String text = cached.optString("body");
        if (System.currentTimeMillis() - cached.optLong("checkedAt") > 86400000L) {
            try {
                text = get(root + "/robots.txt").body;
            } catch (IOException e) {
                if (e instanceof HttpFailure && robotsUnavailable(((HttpFailure) e).status)) {
                    // Preserve previously fetched restrictions if access to the policy temporarily changes.
                    if (
                        ((HttpFailure) e).status == 404 ||
                        ((HttpFailure) e).status == 410 ||
                        text.isBlank()
                    ) text = "";
                } else throw new IOException(
                    "Не удалось проверить robots.txt: " + e.getMessage(),
                    e
                );
            }
            db.saveState(
                "robots:" + root,
                Json.object("body", text, "checkedAt", System.currentTimeMillis())
            );
        }
        return robots(
            text,
            u.encodedPath() + (u.encodedQuery() == null ? "" : "?" + u.encodedQuery())
        );
    }

    static boolean robots(String text, String path) {
        List<String> agents = new ArrayList<>();
        List<String[]> rules = new ArrayList<>();
        boolean directives = false;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.split("#", 2)[0].trim();
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                value = line.substring(colon + 1).trim();
            if (name.equals("user-agent")) {
                if (directives) {
                    agents.clear();
                    directives = false;
                }
                agents.add(value.toLowerCase(Locale.ROOT));
            } else if (name.equals("allow") || name.equals("disallow")) {
                directives = true;
                if (
                    !value.isEmpty() &&
                    agents.stream().anyMatch(a -> a.equals("*") || a.equals("domtrackua"))
                ) rules.add(new String[] { name, value });
            }
        }
        int best = -1;
        boolean allow = true;
        for (String[] rule : rules) {
            String pattern = rule[1];
            String regex = java.util.regex.Pattern.quote(pattern).replace("*", "\\E.*\\Q");
            if (pattern.endsWith("$")) regex =
                java.util.regex.Pattern.quote(pattern.substring(0, pattern.length() - 1)).replace(
                    "*",
                    "\\E.*\\Q"
                ) +
                "$";
            else regex += ".*";
            if (path.matches(regex) && pattern.length() >= best) {
                if (pattern.length() > best || rule[0].equals("allow")) allow = rule[0].equals(
                    "allow"
                );
                best = pattern.length();
            }
        }
        return allow;
    }
}

package ua.domtrack.mobile;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Limits a foreground page reader to one public advert, never an account or search page. */
final class ListingBrowserPolicy {
    private static final Set<String> OLX = Set.of("olx.ua", "www.olx.ua", "m.olx.ua");
    private static final Set<String> RIA = Set.of("dom.ria.com", "dim.ria.com");
    private static final Pattern OLX_PATH = Pattern.compile("/(?:d/)?(?:uk/)?obyavlenie/[^/%\\\\?#]+-ID([A-Za-z0-9]+)\\.html");
    private static final Pattern RIA_PATH = Pattern.compile("/(?:uk/)?realty-prodaja-(?:kvartira|dom|taunhaus)-[a-z0-9-]+-([0-9]+)\\.html");
    private static final Pattern MEDIA = Pattern.compile("(?i).*\\.(?:avif|apng|bmp|gif|heic|heif|ico|jfif|jpe?g|jxl|png|svgz?|tiff?|webp|mp4|m4v|webm|mov|mpe?g|3gp|avi|mkv|wmv|mpd|m4s|ts|mp3|ogg|wav|aac|flac|m4a|opus|aiff|wma|m3u8|pdf)(?:$|[?]).*");
    private static final Pattern PRIVATE = Pattern.compile("(?i)(?:^|/)(?:api|graphql|account|accounts|login|signin|auth|oauth|session|token|captcha|challenge)(?:/|$|[.?#_-])");

    static final class Target {
        final String url, host, path, id, site;
        Target(String url, String host, String path, String id, String site) {
            this.url = url; this.host = host; this.path = path; this.id = id; this.site = site;
        }
    }

    final Target original;

    ListingBrowserPolicy(String url) {
        original = target(url);
        if (original == null) throw new IllegalArgumentException("Нужна открытая HTTPS-ссылка на одно объявление DIM.RIA или OLX.ua.");
    }

    static Target target(String value) {
        try {
            if (value == null || value.length() > 2048 || value.matches("(?s).*[\\x00-\\x20\\\\].*")) return null;
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null ||
                uri.getPort() != -1 || uri.getRawFragment() != null) return null;
            host = host.toLowerCase(Locale.ROOT);
            String site = OLX.contains(host) ? "olx" : RIA.contains(host) ? "ria" : "";
            if (site.isEmpty()) return null;
            String path = uri.getRawPath();
            Matcher match = (site.equals("olx") ? OLX_PATH : RIA_PATH).matcher(path);
            if (!match.matches()) return null;
            String publicHost = site.equals("olx") ? "www.olx.ua" : "dom.ria.com";
            return new Target("https://" + publicHost + path, publicHost, path, match.group(1), site);
        } catch (Exception ignored) { return null; }
    }

    boolean navigation(String url) {
        Target next = target(url);
        return next != null && URI.create(url).getRawQuery() == null &&
            original.host.equals(URI.create(url).getHost().toLowerCase(Locale.ROOT)) &&
            original.path.equals(next.path) && original.id.equals(next.id);
    }

    boolean sameListing(String url) {
        Target next = target(url);
        return next != null && original.site.equals(next.site) && original.path.equals(next.path) && original.id.equals(next.id);
    }

    boolean blockResource(String url, String method, String accept) {
        try {
            URI uri = URI.create(url);
            if (!"GET".equals(method) || !"https".equals(uri.getScheme()) || uri.getHost() == null ||
                uri.getUserInfo() != null || uri.getPort() != -1) return true;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            boolean permittedHost = original.site.equals("olx")
                ? OLX.contains(host) || host.equals("olxcdn.com") || host.endsWith(".olxcdn.com")
                : RIA.contains(host) || host.equals("cdn.ria.com") || host.equals("static.ria.com") ||
                    host.equals("riastatic.com") || host.endsWith(".riastatic.com");
            if (!permittedHost) return true;
            String type = accept == null ? "" : accept.toLowerCase(Locale.ROOT);
            String path = uri.getPath();
            return type.contains("image/") || type.contains("video/") || type.contains("audio/") ||
                path.toLowerCase(Locale.ROOT).contains("/image;") ||
                MEDIA.matcher(path + "?" + uri.getRawQuery()).matches() || PRIVATE.matcher(path).find();
        } catch (Exception ignored) { return true; }
    }
}

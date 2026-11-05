package ua.domtrack.mobile;

import java.net.URI;
import java.util.*;
import org.jsoup.nodes.*;

final class Pagination {

    static Integer page(String href, String base) {
        try {
            URI b = URI.create(base),
                u = b.resolve(href);
            if (
                !Network.sameCatalogSite(u.getHost(), b.getHost()) ||
                !u.getPath().equals(b.getPath()) ||
                u.getFragment() != null
            ) return null;
            String q = u.getQuery();
            if (q == null) return 1;
            if (!q.matches("page=[1-9][0-9]{0,4}")) return null;
            return Integer.parseInt(q.substring(5));
        } catch (Exception e) {
            return null;
        }
    }

    static void verify(Document doc, String url, Sources.Section section, int requested) {
        if (section.source.equals("domik")) return;
        for (Element canonical : doc.select("head link[rel=canonical]")) {
            Integer n = page(canonical.attr("href"), section.base);
            if (
                n == null || n != (section.source.equals("obyava") ? 1 : requested)
            ) throw new IllegalArgumentException("Сайт вернул другой раздел или страницу");
        }
        String selector = section.source.equals("domria-web")
            ? ".pager .page-item.active"
            : section.source.equals("obyava")
                ? ".pagination .pagination__item.active"
                : ".pagination_custom .active";
        List<String> active = doc.select(selector).eachText();
        if (
            active.size() > 1 ||
            (active.size() == 1 && !active.get(0).trim().equals(String.valueOf(requested)))
        ) throw new IllegalArgumentException("Номер полученной страницы не совпадает с запросом");
    }

    static boolean end(Document doc, Sources.Section s, int page, int count) {
        if (s.source.equals("rieltor")) {
            String text = Sources.text(doc.selectFirst("[data-listing-count]"));
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^(\\d[\\d ]*)\\s+оголошен(?:ь|ня)$"
            ).matcher(text);
            if (!m.matches()) return false;
            int total = Integer.parseInt(m.group(1).replace(" ", ""));
            return (
                total > 0 &&
                (int) Math.ceil(total / 20.0) == page &&
                count == total - (page - 1) * 20
            );
        }
        if (s.source.equals("domria-web")) {
            Element pager = doc.selectFirst(".pager.last-page");
            return (
                pager != null &&
                pager
                    .select(".page-item.active")
                    .eachText()
                    .equals(List.of(String.valueOf(page))) &&
                pager.select(".next:not(.disabled):not([aria-disabled=true])").isEmpty() &&
                pager
                    .select(".page-item")
                    .stream()
                    .map(Sources::text)
                    .filter(v -> v.matches("[1-9][0-9]*"))
                    .mapToInt(Integer::parseInt)
                    .max()
                    .orElse(0) ==
                page
            );
        }
        if (s.source.equals("obyava")) return (
            doc.select(".pagination .pagination__item.next.disabled:not(a)").size() == 1 &&
            doc.select(".pagination a[rel=next]").isEmpty() &&
            doc
                .select(".pagination .pagination__item.active")
                .eachText()
                .equals(List.of(String.valueOf(page)))
        );
        return false;
    }
}

package ua.domtrack.mobile;

import java.net.URI;
import java.util.*;
import java.util.regex.*;
import org.json.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.select.Elements;

final class Sources {

    static final String[][] CITIES = {
        { "Київ", "Київська", "" },
        { "Одеса", "Одеська", "odessa" },
        { "Харків", "Харківська", "harkov" },
        { "Львів", "Львівська", "lvov" },
        { "Дніпро", "Дніпропетровська", "dnepr" },
        { "Івано-Франківськ", "Івано-Франківська", "ivano-frankovsk" },
        { "Хмельницький", "Хмельницька", "hmelnitskij" },
        { "Вінниця", "Вінницька", "vinnitsa" },
        { "Рівне", "Рівненська", "rovno" },
        { "Ужгород", "Закарпатська", "uzhgorod" },
        { "Запоріжжя", "Запорізька", "zaporozhje" },
        { "Полтава", "Полтавська", "poltava" },
        { "Тернопіль", "Тернопільська", "ternopol" },
        { "Суми", "Сумська", "sumy" },
        { "Чернігів", "Чернігівська", "chernigov" },
        { "Миколаїв", "Миколаївська", "nikolaev" },
        { "Чернівці", "Чернівецька", "chernovtsy" },
        { "Житомир", "Житомирська", "zhitomir" },
        { "Черкаси", "Черкаська", "cherkassy" },
        { "Кропивницький", "Кіровоградська", "kropyvnytskyi" },
        { "Луцьк", "Волинська", "lutsk" },
        { "Херсон", "Херсонська", "kherson" },
        { "Ірпінь", "Київська", "irpen" },
        { "Буча", "Київська", "bucha-239" },
    };

    static final class Section {

        final String source, city, region, kind, base;

        Section(String s, String c, String r, String k, String b) {
            source = s;
            city = c;
            region = r;
            kind = k;
            base = b;
        }

        String key() {
            return source + ":" + city + ":" + kind;
        }

        String page(int n) {
            return n == 1
                ? base
                : source.equals("domik")
                    ? base + "/page-" + n
                    : base + (base.contains("?") ? "&" : "?") + "page=" + n;
        }
    }

    static final String[] LUN_CITIES = {
        "kyiv",
        "odesa",
        "kharkiv",
        "lviv",
        "dnipro",
        "if",
        "khmelnytskyi",
        "vinnytsia",
        "rivne",
        "uz",
        "zp",
        "poltava",
        "ternopil",
        "sumy",
        "chernihiv",
        "mykolaiv",
        "chernivtsi",
        "zhytomyr",
        "cherkasy",
        "kr",
        "volyn",
        "kherson",
    };

    static List<Section> sections(String city, String kind) {
        List<Section> out = new ArrayList<>();
        String[] row = Arrays.stream(CITIES)
            .filter(c -> Bilingual.city(c[0]).equals(Bilingual.city(city)))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Выберите город из списка"));
        for (String k : List.of("apartment", "house")) {
            if (!kind.equals("both") && !kind.equals(k)) continue;
            out.add(
                new Section(
                    "rieltor",
                    city,
                    row[1],
                    k,
                    "https://rieltor.ua/" +
                        (row[2].isEmpty() ? "" : row[2] + "/") +
                        (k.equals("house") ? "houses-sale/" : "flats-sale/")
                )
            );
            int index = Arrays.asList(CITIES).indexOf(row);
            if (index < LUN_CITIES.length) {
                out.add(
                    new Section(
                        "lun",
                        city,
                        row[1],
                        k,
                        "https://lun.ua/sale/" +
                            LUN_CITIES[index] +
                            "/" +
                            (k.equals("house") ? "houses" : "flats")
                    )
                );
                out.add(
                    new Section(
                        "flatfy",
                        city,
                        row[1],
                        k,
                        "https://flatfy.ua/uk/продаж-" +
                            (k.equals("house") ? "будинків" : "квартир") +
                            "-" +
                            row[0].toLowerCase(Locale.ROOT)
                    )
                );
            }
            if (Bilingual.city(city).equals("запоріжжя")) {
                out.add(
                    new Section(
                        "olx",
                        city,
                        row[1],
                        k,
                        "https://m.olx.ua/uk/nedvizhimost/" +
                            (k.equals("house")
                                    ? "doma/prodazha-domov/"
                                    : "kvartiry/prodazha-kvartir/") +
                            "zaporozhe/"
                    )
                );
                out.add(
                    new Section(
                        "domria-web",
                        city,
                        row[1],
                        k,
                        "https://dom.ria.com/uk/prodazha-" +
                            (k.equals("house") ? "domov" : "kvartir") +
                            "/zaporozhye/"
                    )
                );
                out.add(
                    new Section(
                        "obyava",
                        city,
                        row[1],
                        k,
                        "https://obyava.ua/ru/nedvizhimost/prodazha-" +
                            (k.equals("house") ? "domov" : "kvartir") +
                            "/zaporozhe"
                    )
                );
                out.add(
                    new Section(
                        "domik",
                        city,
                        row[1],
                        k,
                        "https://domik.ua/kupit-" +
                            (k.equals("house") ? "dom" : "kvartiru") +
                            "-zaporozhe"
                    )
                );
            }
        }
        return out;
    }

    static final class Result {

        final List<JSONObject> records;
        final String fingerprint;
        final boolean end;
        final int cards;

        Result(List<JSONObject> r, boolean e, int count) {
            this(
                r,
                e,
                count,
                r
                    .stream()
                    .map(o -> o.optString("url"))
                    .toList()
            );
        }

        Result(List<JSONObject> r, boolean e, int count, List<String> observed) {
            records = r;
            end = e;
            cards = count;
            fingerprint = observed.isEmpty()
                ? ""
                : Model.hash(String.join("\n", new TreeSet<>(observed)));
        }
    }

    static boolean visible(Element node) {
        for (Element e = node; e != null; e = e.parent()) {
            if (
                e.hasAttr("hidden") ||
                e.attr("aria-hidden").equals("true") ||
                e.attr("style").matches("(?is).*(display\\s*:\\s*none|visibility\\s*:\\s*hidden).*")
            ) return false;
            for (String name : List.of(
                "hide",
                "hidden",
                "-hidden",
                "-hide",
                "d-none",
                "sr-only",
                "visually-hidden"
            ))
                if (e.hasClass(name)) return false;
        }
        return true;
    }

    static String text(Element e) {
        if (e == null) return "";
        Element copy = e.clone();
        for (Element n : copy.select(
            "script,style,template,noscript,iframe,[hidden],[aria-hidden=true],.hidden,.hide,.d-none"
        ))
            n.remove();
        return copy
            .text()
            .replace('\u00a0', ' ')
            .replace('\u202f', ' ')
            .replace("\u2060", "")
            .replaceAll("\\s+", " ")
            .trim();
    }

    static JSONObject price(String input, boolean prefix) {
        String s = input
            .replace('\u00a0', ' ')
            .replace('\u202f', ' ')
            .replace("\u2060", "")
            .trim()
            .replaceFirst("(?iu)\\s+торг$", "");
        if (prefix) s = s.replaceFirst("^([$€])\\s*(.+)$", "$2 $1");
        Matcher m = Pattern.compile(
            "^(\\d[\\d ]*(?:[.,]\\d{1,2})?)\\s*(\\$|€|грн\\.?|USD|EUR|UAH)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
        ).matcher(s);
        if (!m.matches()) return null;
        double p;
        try {
            p = Double.parseDouble(m.group(1).replace(" ", "").replace(',', '.'));
        } catch (Exception e) {
            return null;
        }
        if (!Double.isFinite(p) || p <= 0 || p > 1e12) return null;
        String c = m.group(2).toUpperCase(Locale.ROOT);
        return Json.object(
            "price",
            p,
            "currency",
            c.equals("$") ? "USD" : c.equals("€") ? "EUR" : c.startsWith("ГРН") ? "UAH" : c
        );
    }

    static JSONObject uniquePrice(Elements nodes, boolean prefix) {
        JSONObject chosen = null;
        for (Element node : nodes)
            if (visible(node)) {
                JSONObject p = price(text(node), prefix);
                if (
                    p == null || (chosen != null && !chosen.toString().equals(p.toString()))
                ) return null;
                chosen = p;
            }
        return chosen;
    }

    static void params(String text, JSONObject r) {
        for (String part : text.split("[|;\\n]")) {
            java.util.regex.Matcher compact = Pattern.compile(
                "^(\\d{1,3})\\s*/\\s*(\\d{1,3})$"
            ).matcher(part.trim());
            if (compact.matches()) {
                int floor = Integer.parseInt(compact.group(1)),
                    total = Integer.parseInt(compact.group(2));
                if (floor <= total && total <= 200) {
                    Json.put(r, "floor", floor);
                    Json.put(r, "floorsTotal", total);
                }
                continue;
            }
            compact = Pattern.compile("(?iu)^(\\d{1,3})\\s*(?:эт\\.|пов\\.)$").matcher(part.trim());
            if (compact.matches()) {
                Json.put(r, "floor", Integer.parseInt(compact.group(1)));
                continue;
            }
            Matcher m = Pattern.compile(
                "^(\\d{1,3})\\s*(?:[- ]?кімнат(?:а|и|на|ні)?|[- ]?комнат(?:а|ы|ная|ные)?|к)$",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
            ).matcher(part.trim());
            if (m.matches()) {
                Json.put(r, "rooms", Integer.parseInt(m.group(1)));
                continue;
            }
            m = Pattern.compile(
                "^(\\d+(?:[.,]\\d+)?)\\s*(?:/\\s*\\d+(?:[.,]\\d+)?\\s*){0,2}м[²2]$"
            ).matcher(part.trim());
            if (m.matches()) {
                Json.put(r, "area", Double.parseDouble(m.group(1).replace(',', '.')));
                continue;
            }
            m = Pattern.compile(
                "^(?:поверх|этаж)\\s*(\\d{1,3})\\s*(?:з|із|из|/)\\s*(\\d{1,3})$|^(\\d{1,3})\\s*(?:поверх|этаж)\\s*(?:з|із|из|/)\\s*(\\d{1,3})$",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
            ).matcher(part.trim());
            if (m.matches()) {
                int f = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(3)),
                    t = Integer.parseInt(m.group(2) != null ? m.group(2) : m.group(4));
                if (f <= t && t <= 200) {
                    Json.put(r, "floor", f);
                    Json.put(r, "floorsTotal", t);
                }
                continue;
            }
            m = Pattern.compile(
                "^(?:Этажей\\s+)?(\\d{1,3})(?:\\s*(?:поверх(?:и|ів)?|этаж(?:а|ей|ный)?))?$",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
            ).matcher(part.trim());
            if (m.matches() && part.matches("(?iu).*(этаж|поверх).*")) Json.put(
                r,
                "floorsTotal",
                Integer.parseInt(m.group(1))
            );
        }
    }

    static String validatedLink(Element link, String base, String regex) {
        if (link == null) return null;
        try {
            URI b = URI.create(base),
                u = b.resolve(link.attr("href"));
            if (
                !u.getScheme().equals("https") ||
                !Network.sameCatalogSite(u.getHost(), b.getHost()) ||
                u.getUserInfo() != null ||
                u.getPort() != -1 ||
                !u.getPath().matches(regex)
            ) return null;
            return new URI("https", u.getHost(), u.getPath(), null).toString();
        } catch (Exception e) {
            return null;
        }
    }

    static String cityIdentity(String city) {
        String value = Model.normalized(city);
        return Bilingual.city(value);
    }

    static Result parse(String html, String pageUrl, Section s, int page) {
        Document doc = Jsoup.parse(html, pageUrl);
        if (s.source.equals("lun") || s.source.equals("flatfy")) return AggregatorPages.parse(
            doc,
            pageUrl,
            s,
            page
        );
        if (s.source.equals("olx")) return OlxPage.parse(doc, pageUrl, s, page);
        String selector = s.source.equals("rieltor")
            ? ".catalog-items-container .catalog-card"
            : s.source.equals("domria-web")
                ? ".search-result-list section.realty-item"
                : s.source.equals("obyava")
                    ? ".js-classifieds-list .single-item"
                    : ".viewTile_object";
        Pagination.verify(doc, pageUrl, s, page);
        Elements cards = doc.select(selector);
        List<String> observed = new ArrayList<>();
        LinkedHashMap<String, JSONObject> records = new LinkedHashMap<>();
        Set<String> conflicts = new HashSet<>();
        int count = 0;
        for (Element card : cards) {
            if (!visible(card)) continue;
            count++;
            for (Element identity : card.select("a[href]")) {
                String href = identity.absUrl("href");
                if (
                    href.matches(
                        "https://[^/]+/.*(?:realty-prodaja-|sale/view/|-[0-9]{5,}\\.html|id[0-9]+\\.html).*"
                    )
                ) observed.add(href);
            }
            Element link;
            String url,
                title,
                city = s.city,
                address = "",
                district = "";
            JSONObject p;
            String param = "";
            switch (s.source) {
                case "rieltor":
                    link = card.selectFirst("a.catalog-card-media");
                    url = validatedLink(
                        link,
                        pageUrl,
                        "/(?:[a-z0-9-]+/)?" +
                            (s.kind.equals("house") ? "houses" : "flats") +
                            "-sale/view/[1-9][0-9]{0,11}/"
                    );
                    p = uniquePrice(card.select(".catalog-card-price-title"), false);
                    Element locality = card
                        .select(".catalog-card-region a")
                        .stream()
                        .filter(a ->
                            a
                                .attr("href")
                                .matches(
                                    "(?:https://rieltor.ua)?/(?:[a-z0-9-]+/)?(?:flats|houses)-sale/"
                                )
                        )
                        .findFirst()
                        .orElse(null);
                    if (locality == null) continue;
                    city = text(locality).replaceFirst("^(?:м\\.|місто|г\\.)\\s*", "");
                    address = text(card.selectFirst(".catalog-card-address"));
                    title = address;
                    district = card
                        .select(".catalog-card-region a")
                        .stream()
                        .filter(a -> a != locality && text(a).matches("(?iu).*(р-н|район).*"))
                        .map(Sources::text)
                        .findFirst()
                        .orElse("");
                    param = String.join("|", card.select(".catalog-card-details-row").eachText());
                    break;
                case "domria-web":
                    link = card.selectFirst(".tit a");
                    url = validatedLink(
                        link,
                        pageUrl,
                        "/uk/realty-prodaja-" +
                            (s.kind.equals("house") ? "(?:dom|taunhaus)" : "kvartira") +
                            "-zaporoje-[a-z0-9-]+-[1-9][0-9]{0,11}\\.html"
                    );
                    p = uniquePrice(card.select("b[data-tm=realty-full-price]"), false);
                    Elements localities = card.select(
                        "span.mb-5.i-block:not(.u-line):not([data-level]),[data-level=city].mb-5.i-block"
                    );
                    if (localities.size() != 1) continue;
                    city = text(localities.first());
                    address = text(link);
                    title = address;
                    district = String.join(
                        ", ",
                        card.select("[data-level=area],a[href*=rayon-].mb-5.i-block").eachText()
                    );
                    param = String.join("|", card.select(".realty-char").eachText());
                    break;
                case "obyava":
                    link = card.selectFirst("a.single-item__title");
                    url = validatedLink(link, pageUrl, "/ru/[a-z0-9-]+-[1-9][0-9]{0,11}\\.html");
                    title = text(link);
                    p = uniquePrice(card.select(".single-item__price"), false);
                    city = text(card.selectFirst(".single-item__location"));
                    param = text(card.selectFirst(".single-item__params"));
                    if (
                        title.matches(
                            "(?iu).*(?:аренда|оренда|сдам|здам|част[ьиі] |доля |половина|общежит|гостинк|комнату |кімнату ).*"
                        )
                    ) continue;
                    break;
                default:
                    link = card.selectFirst("a.viewTile__link_fill");
                    url = validatedLink(
                        link,
                        pageUrl,
                        "/nedvizhimost/zaporozhe-[a-z0-9-]+-id[1-9][0-9]{0,11}\\.html"
                    );
                    address = text(card.selectFirst(".viewTile__location"));
                    title = address;
                    p = uniquePrice(card.select(".viewTile__text_priceMain"), true);
                    String classes = text(card.selectFirst(".viewTile__classes"));
                    Matcher spec = Pattern.compile(
                        s.kind.equals("house")
                            ? "^(Дом|Коттедж|Таунхаус|Дача),\\s*(\\d+(?:[.,]\\d+)?)\\s*м[²2]$"
                            : "^(?:(\\d{1,3})-комнатная|Свободная планировка),\\s*(\\d+(?:[.,]\\d+)?)\\s*м[²2]$"
                    ).matcher(classes);
                    if (!spec.matches()) continue;
                    param =
                        String.join("|", card.select(".viewTile__params li").eachText()) +
                        "|" +
                        spec.group(2) +
                        " м²";
                    if (s.kind.equals("apartment") && spec.group(1) != null) param +=
                        "|" + spec.group(1) + " комнат";
                    String desc = text(card.selectFirst(".viewTile__description"));
                    if (
                        (title + " " + desc).matches(
                            "(?iu).*(?:част[ьи] дома|половина дома|в селе|у селі|селище Сонячн|пос[её]лок Солнечн).*"
                        )
                    ) continue;
                    break;
            }
            if (
                url == null ||
                p == null ||
                !cityIdentity(city).equals(cityIdentity(s.city)) ||
                title.isBlank()
            ) continue;
            city = s.city;
            JSONObject r = Json.object(
                "url",
                url,
                "externalId",
                Model.hash(url),
                "source",
                s.source,
                "title",
                title,
                "address",
                address,
                "district",
                district,
                "city",
                city,
                "region",
                s.region,
                "kind",
                s.kind,
                "deal",
                "sale",
                "price",
                p.optDouble("price"),
                "currency",
                p.optString("currency"),
                "sellerType",
                "unknown",
                "status",
                "active",
                "observedAt",
                Model.now()
            );
            params(param, r);
            String badges = String.join(
                "|",
                card
                    .select(
                        ".catalog-card-author-subtitle,.catalog-card-additional,.label-item,.label-border"
                    )
                    .eachText()
            );
            if (
                badges.matches(
                    "(?iu).*(?:^|\\|)(?:від власника|от собственника|власник|собственник)(?:\\||$).*"
                )
            ) Json.put(r, "sellerType", "owner");
            else if (badges.matches("(?iu).*(забудовник|застройщик).*")) Json.put(
                r,
                "sellerType",
                "developer"
            );
            else if (badges.matches("(?iu).*(рієлтор|риелтор|агентство).*")) Json.put(
                r,
                "sellerType",
                "agent"
            );
            ListingPhotos.card(card, r);
            Json.put(
                r,
                "description",
                text(
                    card.selectFirst(
                        ".viewTile__description,.catalog-card-description,.realty-description,.realty-desc,.desc"
                    )
                )
            );
            extractGeo(card, r);
            JSONObject old = records.get(url);
            if (
                old != null &&
                (old.optDouble("price") != r.optDouble("price") ||
                    !old.optString("currency").equals(r.optString("currency")))
            ) {
                records.remove(url);
                conflicts.add(url);
            } else if (!conflicts.contains(url)) records.put(url, r);
        }
        boolean end = Pagination.end(doc, s, page, count);
        if (s.source.equals("domik")) {
            String counter = text(doc.selectFirst(".pageSearch__text_foundCount"));
            Matcher m = Pattern.compile(".*?(\\d+)\\s+.*").matcher(counter);
            int max = doc
                .select("a[href*=page-]")
                .stream()
                .mapToInt(e -> {
                    Matcher n = Pattern.compile("/page-(\\d+)$").matcher(e.attr("href"));
                    return n.find() ? Integer.parseInt(n.group(1)) : 1;
                })
                .max()
                .orElse(1);
            if (counter.matches("[0-9]{1,7}")) {
                int total = Integer.parseInt(counter);
                Set<Integer> pages = new HashSet<>();
                pages.add(page);
                for (Element link : doc.select(".paginator .paginator__link")) {
                    String value = text(link);
                    if (value.matches("[1-9][0-9]*")) pages.add(Integer.parseInt(value));
                }
                int last = pages.stream().mapToInt(Integer::intValue).max().orElse(1);
                boolean contiguous = pages.size() == last;
                end =
                    total > 0 &&
                    page == last &&
                    contiguous &&
                    last == (int) Math.ceil(total / 30.0) &&
                    count == Math.min(30, total - (page - 1) * 30);
            }
        }
        if (count == 0 && !end) throw new IllegalArgumentException(
            "Карточки не найдены: разметка изменилась или доступ ограничен"
        );
        if (count > 0 && records.isEmpty()) throw new IllegalArgumentException(
            "Карточки есть, но город, тип или полная цена не подтверждены"
        );
        return new Result(new ArrayList<>(records.values()), end, count, observed);
    }

    static void extractGeo(Element scope, JSONObject r) {
        for (Element script : scope.select("script[type=application/ld+json]"))
            try {
                Object raw = new JSONTokener(script.data()).nextValue();
                walkLd(raw, r, false);
            } catch (Exception ignored) {}
    }

    static void walkLd(Object raw, JSONObject r, boolean details) {
        if (raw instanceof JSONArray) {
            for (int i = 0; i < ((JSONArray) raw).length(); i++) walkLd(
                ((JSONArray) raw).opt(i),
                r,
                details
            );
            return;
        }
        if (!(raw instanceof JSONObject o)) return;
        JSONObject geo = o.optJSONObject("geo");
        if (geo != null) {
            double lat = geo.optDouble("latitude", Double.NaN),
                lon = geo.optDouble("longitude", Double.NaN);
            if (lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180) {
                Json.put(r, "latitude", lat);
                Json.put(r, "longitude", lon);
                Json.put(r, "locationAccuracy", "source-unspecified");
            }
        }
        Object graph = o.opt("@graph");
        if (graph != null) walkLd(graph, r, details);
    }

    static List<JSONObject> importRecords(String html, String requestedUrl, String responseUrl) {
        if (ListingLinks.olxHost(requestedUrl))
            return List.of(OlxDetail.parse(html, requestedUrl, responseUrl, null));
        List<JSONObject> rows = structured(html, responseUrl);
        for (JSONObject row : rows) Json.put(row, "structured", true);
        return rows;
    }

    static String watchUrl(JSONObject row) {
        if (ListingLinks.olxWatch(row)) {
            ListingLinks.Olx detail = ListingLinks.olx(row.optString("url"));
            if (detail == null) throw new IllegalArgumentException("OLX: исходная ссылка объявления не подтверждена");
            return detail.url;
        }
        return row.optBoolean("structured") ? row.optString("url") : row.optString("catalogUrl", row.optString("url"));
    }

    static List<JSONObject> watchRecords(String html, String responseUrl, JSONObject previous) {
        List<JSONObject> rows;
        if (ListingLinks.olxWatch(previous))
            rows = List.of(OlxDetail.parse(html, previous.optString("url"), responseUrl, previous));
        else if (previous.optBoolean("structured")) rows = structured(html, responseUrl);
        else {
            Section section = sections(previous.optString("city"), previous.optString("kind")).stream()
                .filter(s -> s.source.equals(previous.optString("source"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Раздел наблюдаемого объявления не поддерживается"));
            rows = parse(html, responseUrl, section, previous.optInt("catalogPage", 1)).records;
        }
        return List.of(ListingLinks.watched(rows, previous));
    }

    static List<JSONObject> structured(String html, String url) {
        if (ListingLinks.olxHost(url)) return List.of(OlxDetail.parse(html, url, url, null));
        Document doc = Jsoup.parse(html, url);
        List<JSONObject> out = new ArrayList<>(RiaPage.parse(doc, url));
        if (!out.isEmpty()) return out;
        // Generic JSON-LD can describe a recommended property. For RIA it must not be
        // relabelled with this page's URL after the linked-product binding failed.
        if (List.of("dom.ria.com", "dim.ria.com").contains(Objects.toString(URI.create(url).getHost(), "").toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("RIA: структурированные данные не привязаны к этому объявлению. Можно открыть оригинал или добавить объект вручную.");
        for (Element e : doc.select("script[type=application/ld+json]"))
            try {
                collectLd(new JSONTokener(e.data()).nextValue(), url, out);
            } catch (Exception ignored) {}
        if (out.isEmpty()) throw new IllegalArgumentException(
            "Нет однозначных структурированных данных продажи. Можно добавить объект вручную."
        );
        return out;
    }

    static void collectLd(Object raw, String url, List<JSONObject> out) {
        if (raw instanceof JSONArray) {
            for (int i = 0; i < ((JSONArray) raw).length(); i++) collectLd(
                ((JSONArray) raw).opt(i),
                url,
                out
            );
            return;
        }
        if (!(raw instanceof JSONObject o)) return;
        if (o.has("@graph")) collectLd(o.opt("@graph"), url, out);
        String type = o.optString("@type");
        if (
            !List.of(
                "Apartment",
                "House",
                "SingleFamilyResidence",
                "Residence",
                "RealEstateListing",
                "Product"
            ).contains(type)
        ) return;
        JSONObject offers = o.optJSONObject("offers");
        if (offers == null) return;
        String business = offers.optString("businessFunction");
        if (!business.endsWith("Sell")) return;
        JSONObject address = o.optJSONObject("address");
        if (address == null) return;
        String kind = type.equals("House") || type.equals("SingleFamilyResidence")
            ? "house"
            : type.equals("Apartment")
                ? "apartment"
                : "";
        if (kind.isEmpty()) return;
        JSONObject r = Json.object(
            "url",
            url,
            "source",
            URI.create(url).getHost(),
            "externalId",
            Model.hash(url),
            "title",
            o.optString("name"),
            "city",
            address.optString("addressLocality"),
            "address",
            address.optString("streetAddress"),
            "deal",
            "sale",
            "kind",
            kind,
            "price",
            offers.optDouble("price"),
            "currency",
            offers.optString("priceCurrency"),
            "observedAt",
            Model.now(),
            "description",
            o.optString("description")
        );
        JSONObject area = o.optJSONObject("floorSize");
        if (area != null) Json.put(r, "area", area.optDouble("value"));
        if (o.has("numberOfRooms")) Json.put(r, "rooms", o.optInt("numberOfRooms"));
        ListingPhotos.metadata(r, o.opt("image"), url);
        walkLd(o, r, true);
        Model.validate(r);
        out.add(r);
    }
}

package ua.domtrack.mobile;

import java.util.*;
import org.json.*;
import org.jsoup.nodes.*;

/** Conservative public-card adapter. Live access may be denied by OLX (reported by Scanner). */
final class OlxPage {

    static Sources.Result parse(Document doc, String url, Sources.Section s, int page) {
        Pagination.verify(doc, url, s, page);
        List<JSONObject> records = new ArrayList<>();
        List<String> observed = new ArrayList<>();
        int count = 0;
        for (Element card : doc.select("[data-cy=l-card],[data-testid=l-card]")) {
            if (!Sources.visible(card)) continue;
            count++;
            String link = Sources.validatedLink(
                card.selectFirst("a[href*=/obyavlenie/]"),
                url,
                "/(?:d/)?(?:uk/)?obyavlenie/[^/]+-ID[A-Za-z0-9]+\\.html"
            );
            if (link == null) continue;
            observed.add(link);
            String locality = Sources.text(card.selectFirst("[data-testid=location-date]"));
            String city = locality.split(",| - | — ", 2)[0].trim();
            if (!Bilingual.city(city).equals(Bilingual.city(s.city))) continue;
            JSONObject price = Sources.uniquePrice(card.select("[data-testid=ad-price]"), true);
            String title = Sources.text(card.selectFirst("h4,h6,[data-cy=ad-card-title]"));
            if (
                price == null ||
                title.isBlank() ||
                title.matches(
                    "(?iu).*(?:сдам|здам|аренда|оренда|кімнату|комнату|част[ьиі] (?:дома|будинку)).*"
                )
            ) continue;
            JSONObject r = Json.object(
                "url",
                link,
                "source",
                "olx",
                "title",
                title,
                "city",
                s.city,
                "region",
                s.region,
                "kind",
                s.kind,
                "deal",
                "sale",
                "price",
                price.optDouble("price"),
                "currency",
                price.optString("currency"),
                "priceEvidence",
                "display",
                "sellerType",
                "unknown",
                "observedAt",
                Model.now()
            );
            ListingPhotos.card(card, r);
            records.add(r);
        }
        if (records.isEmpty()) throw new IllegalArgumentException(
            "OLX: публичные карточки не подтверждены; доступ может быть ограничен"
        );
        // No end-of-catalog claim without a verified last-page signal.
        return new Sources.Result(records, false, count, observed);
    }
}

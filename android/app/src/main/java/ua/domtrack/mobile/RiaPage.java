package ua.domtrack.mobile;

import java.net.URI;
import java.util.*;
import java.util.regex.*;
import org.json.*;
import org.jsoup.nodes.*;

/** Joins the linked public Product offer and Apartment/House schemas on the same canonical URL. */
final class RiaPage {

    static List<JSONObject> parse(Document doc, String url) {
        URI uri = URI.create(url);
        if (
            !"dom.ria.com".equals(uri.getHost()) ||
            !uri
                .getPath()
                .matches(
                    "/(?:uk/)?realty-prodaja-(?:kvartira|dom|taunhaus)-[a-z0-9-]+-[0-9]+\\.html"
                )
        ) return List.of();
        ArrayList<JSONObject> nodes = new ArrayList<>();
        for (Element script : doc.select("script[type=application/ld+json]"))
            try {
                collect(new JSONTokener(script.data()).nextValue(), nodes);
            } catch (Exception ignored) {}
        JSONObject offerProduct = null,
            property = null;
        for (JSONObject node : nodes) {
            if (!url.equals(node.optString("url", node.optString("@id")))) continue;
            if (
                node.optString("@type").equals("Product") && node.optJSONObject("offers") != null
            ) offerProduct = node;
            if (
                List.of("Apartment", "House", "SingleFamilyResidence").contains(
                    node.optString("@type")
                )
            ) property = node;
        }
        if (offerProduct == null || property == null) return List.of();
        JSONObject offer = offerProduct.optJSONObject("offers");
        if (
            !offer
                .optString("businessFunction")
                .toLowerCase(Locale.ROOT)
                .matches("(?:https?://[^ ]*/)?sell") ||
            !url.equals(offer.optString("url"))
        ) return List.of();
        String title = offerProduct.optString("name"),
            city = "";
        String locality = offer.optString("areaServed");
        for (String part : locality.split(","))
            for (String[] row : Sources.CITIES)
                if (Bilingual.city(part.trim()).equals(Bilingual.city(row[0]))) city = row[0];
        if (city.isBlank()) return List.of();
        String kind = property.optString("@type").equals("Apartment") ? "apartment" : "house";
        JSONObject record = Json.object(
            "url",
            url,
            "source",
            "domria-web",
            "city",
            city,
            "kind",
            kind,
            "deal",
            "sale",
            "title",
            title,
            "price",
            offer.optDouble("price"),
            "currency",
            offer.optString("priceCurrency"),
            "description",
            offerProduct.optString("description"),
            "status",
            "active",
            "observedAt",
            Model.now()
        );
        Matcher id = Pattern.compile("-([0-9]+)\\.html$").matcher(url);
        if (id.find()) Json.put(record, "externalId", id.group(1));
        JSONObject size = property.optJSONObject("floorSize");
        if (size != null && size.optDouble("value", 0) > 0) Json.put(
            record,
            "area",
            size.optDouble("value")
        );
        if (property.optInt("numberOfRooms", 0) > 0) Json.put(
            record,
            "rooms",
            property.optInt("numberOfRooms")
        );
        String text = doc.body() == null ? "" : Sources.text(doc.body());
        Matcher floors = Pattern.compile(
            "(?iu)(\\d{1,3})\\s*(?:поверх|этаж)\\s*(?:з|из|із)\\s*(\\d{1,3})"
        ).matcher(text);
        if (floors.find()) Sources.params(floors.group(), record);
        for (String part : locality.split(","))
            if (part.trim().startsWith("район ")) Json.put(
                record,
                "district",
                part.trim().substring(6)
            );
        Matcher address = Pattern.compile(
            "(?iu)на (?:вул\\.|улице|вулиці)\\s+(.+?)\\s*[•·]\\s*ID"
        ).matcher(title);
        if (address.find()) Json.put(record, "address", address.group(1).trim());
        Sources.walkLd(property, record, true);
        if (offerProduct.optString("image").startsWith("https://")) Json.put(
            record,
            "image",
            offerProduct.optString("image")
        );
        JSONObject seller = offer.optJSONObject("seller");
        Json.put(
            record,
            "sellerType",
            seller != null && seller.optString("jobTitle").equalsIgnoreCase("Realtor")
                ? "agent"
                : "unknown"
        );
        Model.validate(record);
        return List.of(record);
    }

    private static void collect(Object object, List<JSONObject> nodes) {
        if (object instanceof JSONArray list) {
            for (int i = 0; i < list.length(); i++) collect(list.opt(i), nodes);
        } else if (object instanceof JSONObject node) {
            nodes.add(node);
            if (node.has("@graph")) collect(node.opt("@graph"), nodes);
        }
    }
}

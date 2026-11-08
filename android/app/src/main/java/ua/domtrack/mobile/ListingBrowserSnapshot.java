package ua.domtrack.mobile;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** A bounded public DOM export. Runtime stores, cookies, form values and bootstrap data are never read. */
final class ListingBrowserSnapshot {
    static final int MAX_BYTES = 256 * 1024;
    private static final Pattern DENIAL = Pattern.compile(
        "(?iu)access\\s+denied|request\\s+(?:blocked|rejected)|captcha|доступ\\s+(?:заборонено|обмежено|запрещ[её]н|ограничен)"
    );

    static final class Result {
        final String url, html;
        Result(String url, String html) { this.url = url; this.html = html; }
    }

    static Result decode(String callback, String requestedUrl, String browserUrl) {
        try {
            ListingBrowserPolicy policy = new ListingBrowserPolicy(requestedUrl);
            if (!policy.navigation(browserUrl) || callback == null || callback.length() > MAX_BYTES * 8)
                throw new IllegalArgumentException("Исходное объявление в браузере не подтверждено.");
            Object encoded = new JSONTokener(callback).nextValue();
            if (!(encoded instanceof String)) throw new IllegalArgumentException("Нет доступной страницы объявления.");
            JSONObject payload = new JSONObject((String) encoded);
            if (!payload.optString("error").isEmpty()) throw new IllegalArgumentException(payload.optString("error"));
            if (!policy.navigation(payload.optString("url")))
                throw new IllegalArgumentException("Страница перешла на другой адрес; импорт остановлен.");
            String html = payload.optString("html");
            if (html.isBlank() || html.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
                throw new IllegalArgumentException("Видимая страница слишком большая для безопасного импорта.");
            Document doc = Jsoup.parse(html, browserUrl);
            List<Element> canonical = doc.select("link[rel]").stream().filter(e ->
                Arrays.asList(e.attr("rel").toLowerCase(Locale.ROOT).trim().split("\\s+")).contains("canonical")
            ).toList();
            String url = canonical.size() == 1 ? canonical.get(0).attr("href") : "";
            if (canonical.size() != 1 || canonical.get(0).parent() != doc.head() ||
                !policy.sameListing(url) || URI.create(url).getRawQuery() != null)
                throw new IllegalArgumentException("Единственная исходная ссылка объявления в заголовке страницы не подтверждена.");
            if (doc.body() == null || doc.body().text().isBlank() || DENIAL.matcher(doc.body().text()).find())
                throw new IllegalArgumentException("Площадка ограничила доступ или требует проверку. Откройте оригинал либо добавьте объект вручную.");
            return new Result(url, html);
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Не удалось прочитать доступную страницу объявления.", e); }
    }

    static String script(boolean includePublicJsonLd) {
        return "(function(){const keepLd=" + includePublicJsonLd + ";" + """
            try {
                const limit=262144;
                if (!document.body || document.querySelectorAll('*').length>20000)
                    throw new Error('Страница ещё не готова или слишком сложная.');
                const out=document.implementation.createHTMLDocument('');
                out.head.textContent=''; out.body.textContent='';
                const canonical=e=>e.tagName==='LINK' && (e.getAttribute('rel')||'').toLowerCase().trim().split(/\\s+/).includes('canonical');
                // Preserve every canonical, including hidden metadata and invalid body placement.
                for (const e of document.querySelectorAll('link[rel]')) if (canonical(e)) {
                    const link=out.createElement('link');
                    link.setAttribute('rel',e.getAttribute('rel'));
                    link.setAttribute('href',e.getAttribute('href')||'');
                    (e.parentElement===document.head?out.head:out.body).appendChild(link);
                }
                if (keepLd) for (const e of document.querySelectorAll('script[type]'))
                    if ((e.getAttribute('type')||'').toLowerCase().trim()==='application/ld+json') {
                        if (e.textContent.length>limit) throw new Error('Структурированные данные слишком большие.');
                        const s=out.createElement('script'); s.type='application/ld+json'; s.textContent=e.textContent;
                        out.head.appendChild(s);
                    }
                const excluded=new Set(['SCRIPT','STYLE','TEMPLATE','NOSCRIPT','IFRAME','FRAME','OBJECT','EMBED','IMG','PICTURE','SOURCE','VIDEO','AUDIO','CANVAS','SVG','INPUT','TEXTAREA','SELECT','BUTTON','LINK','META']);
                let textSize=0, nodes=0;
                function copy(node) {
                    if (++nodes>20000) throw new Error('Страница слишком сложная.');
                    if (node.nodeType===3) {
                        textSize+=node.textContent.length;
                        if (textSize>limit) throw new Error('Видимый текст страницы слишком большой.');
                        return out.createTextNode(node.textContent);
                    }
                    if (node.nodeType!==1 || excluded.has(node.tagName) || node.hidden || node.inert ||
                        (node.getAttribute('aria-hidden')||'').trim().toLowerCase()==='true') return null;
                    const s=getComputedStyle(node);
                    if (s.display==='none' || s.visibility==='hidden' || s.visibility==='collapse' ||
                        Number(s.opacity)===0 || s.contentVisibility==='hidden' || parseFloat(s.fontSize)===0 ||
                        s.color==='rgba(0, 0, 0, 0)' || /opacity\\(0(?:\\.0+)?\\)/.test(s.filter) ||
                        (s.clip && s.clip!=='auto') || (!node.getClientRects().length && s.display!=='contents')) return null;
                    const rect=node.getBoundingClientRect();
                    if ((s.overflow==='hidden' || s.overflow==='clip') && (!rect.width || !rect.height)) return null;
                    const el=out.createElement(node.tagName.toLowerCase());
                    const testId=node.getAttribute('data-testid');
                    if (testId && testId.length<200) el.setAttribute('data-testid',testId);
                    if (node.tagName==='A') {
                        const href=node.getAttribute('href')||'';
                        if (href.length<=2048) el.setAttribute('href',href);
                    }
                    for (const child of node.childNodes) { const c=copy(child); if(c)el.appendChild(c); }
                    return el;
                }
                const body=copy(document.body);
                if(body) while(body.firstChild) out.body.appendChild(body.firstChild);
                const html='<!doctype html>'+out.documentElement.outerHTML;
                if(new TextEncoder().encode(html).length>limit) throw new Error('Видимая страница слишком большая для импорта.');
                return JSON.stringify({url:location.href,html:html});
            } catch(e) { return JSON.stringify({error:String(e.message||'Не удалось прочитать страницу.')}); }
            })()
            """;
    }
}

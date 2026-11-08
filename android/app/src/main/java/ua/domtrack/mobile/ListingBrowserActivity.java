package ua.domtrack.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.view.View;
import android.webkit.*;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayInputStream;
import java.util.Map;

/** A short-lived, user-visible reader in its own WebView process and storage profile. */
public class ListingBrowserActivity extends Activity {
    private static final long READER_MILLIS = 30_000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView page;
    private TextView status;
    private Button importButton;
    private ListingBrowserPolicy policy;
    private String requestedUrl;
    private long deadline;
    private volatile boolean stopped;
    private boolean foreground;
    private final Runnable timeout = () -> stopPage(
        "30 секунд истекли. Загрузка остановлена. Если объявление не открылось, откройте оригинал или добавьте объект вручную."
    );

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setResult(RESULT_CANCELED);
        requestedUrl = getIntent().getStringExtra("url");
        try {
            policy = new ListingBrowserPolicy(requestedUrl);
            requestedUrl = policy.original.url;
            policy = new ListingBrowserPolicy(requestedUrl);
        } catch (IllegalArgumentException e) { finish(); return; }
        deadline = SystemClock.elapsedRealtime() + READER_MILLIS;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(dp(12), dp(12), dp(12), dp(8));
        root.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
            view.setPadding(dp(12)+bars.left,dp(12)+bars.top,dp(12)+bars.right,dp(8)+bars.bottom);return insets;
        });
        TextView title = new TextView(this);
        title.setText("Открытая страница объявления");
        title.setTextSize(19);
        title.setTextColor(Color.rgb(23, 39, 47));
        root.addView(title);
        TextView address = new TextView(this);
        address.setText(policy.original.url);
        address.setTextSize(12);
        root.addView(address);
        status = new TextView(this);
        status.setText("До 30 секунд на открытую страницу. Импорт переносит доступный текст и публичные данные объявления. Фото не загружаются.");
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);
        LinearLayout actions = new LinearLayout(this);
        importButton = new Button(this);
        importButton.setText("Импортировать");
        importButton.setEnabled(false);
        importButton.setOnClickListener(v -> importVisiblePage());
        actions.addView(importButton, new LinearLayout.LayoutParams(0, -2, 1));
        Button external = new Button(this);
        external.setText("Оригинал");
        external.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(requestedUrl)).addCategory(Intent.CATEGORY_BROWSABLE)); }
            catch (Exception e) { status.setText("Нет приложения для открытия HTTPS-ссылки."); }
        });
        actions.addView(external, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = new Button(this);
        close.setText("Закрыть");
        close.setOnClickListener(v -> finish());
        actions.addView(close, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(actions);
        page = new WebView(this);
        configure(page);
        page.setWebViewClient(new ReaderClient());
        page.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, false, false);
            }
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, Message result) { return false; }
            @Override public boolean onShowFileChooser(WebView view, android.webkit.ValueCallback<Uri[]> callback, FileChooserParams params) { return false; }
            @Override public boolean onJsAlert(WebView view, String url, String message, JsResult result) { result.cancel(); return true; }
            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) { result.cancel(); return true; }
            @Override public boolean onJsPrompt(WebView view, String url, String message, String value, JsPromptResult result) { result.cancel(); return true; }
        });
        page.setDownloadListener((url, agent, disposition, mime, length) ->
            status.setText("Файлы не загружаются. Для работы с площадкой откройте оригинал."));
        root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        page.loadUrl(requestedUrl);
    }

    @SuppressLint("SetJavaScriptEnabled")
    static void configure(WebView view) {
        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setLoadsImagesAutomatically(false);
        settings.setBlockNetworkImage(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        CookieManager.getInstance().setAcceptCookie(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
        ServiceWorkerController controller = ServiceWorkerController.getInstance();
        controller.setServiceWorkerClient(new ServiceWorkerClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) { return blocked(); }
        });
        ServiceWorkerWebSettings workers = controller.getServiceWorkerWebSettings();
        workers.setBlockNetworkLoads(true);
        workers.setAllowContentAccess(false);
        workers.setAllowFileAccess(false);
        workers.setCacheMode(WebSettings.LOAD_NO_CACHE);
    }

    private void importVisiblePage() {
        if (stopped || !foreground || page == null) return;
        if (SystemClock.elapsedRealtime() >= deadline) { timeout.run(); return; }
        importButton.setEnabled(false);
        status.setText("Читаем доступный текст объявления…");
        page.evaluateJavascript(ListingBrowserSnapshot.script(policy.original.site.equals("ria")), value -> {
            if (stopped || !foreground || page == null || isFinishing()) return;
            if (SystemClock.elapsedRealtime() >= deadline) { timeout.run(); return; }
            try {
                ListingBrowserSnapshot.Result snapshot = ListingBrowserSnapshot.decode(value, requestedUrl, page.getUrl());
                setResult(RESULT_OK, new Intent().putExtra("url", snapshot.url).putExtra("html", snapshot.html));
                finish();
            } catch (IllegalArgumentException e) { stopPage(e.getMessage()); }
        });
    }

    private void stopPage(String message) {
        if (stopped) return;
        stopped = true;
        handler.removeCallbacks(timeout);
        if (importButton != null) importButton.setEnabled(false);
        if (status != null) status.setText(message);
        if (page != null) {
            page.stopLoading();
            page.getSettings().setJavaScriptEnabled(false);
            page.onPause();
            page.pauseTimers();
            destroyPage();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        if (page != null && !stopped) {
            page.onResume();
            page.resumeTimers();
            handler.postDelayed(timeout, Math.max(0, deadline - SystemClock.elapsedRealtime()));
        }
    }

    @Override protected void onPause() {
        foreground = false;
        handler.removeCallbacks(timeout);
        stopPage("Чтение остановлено после ухода с экрана. Для продолжения откройте оригинал или запустите чтение заново.");
        super.onPause();
    }

    @Override protected void onStop() {
        super.onStop();
        if (!isFinishing()) finish();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        destroyPage();
        super.onDestroy();
    }

    private void destroyPage() {
        if (page != null) {
            page.clearCache(true);
            page.clearHistory();
            page.removeAllViews();
            if (page.getParent() instanceof android.view.ViewGroup parent) parent.removeView(page);
            page.destroy();
            page = null;
            // DomTrackApp selected this separate profile before any WebView API was called.
            CookieManager.getInstance().removeAllCookies(null);
            WebStorage.getInstance().deleteAllData();
            WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword();
            WebViewDatabase.getInstance(this).clearFormData();
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", Map.of("Cache-Control", "no-store"), new ByteArrayInputStream(new byte[0]));
    }

    private final class ReaderClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            boolean allow = !stopped && request.isForMainFrame() && "GET".equals(request.getMethod()) && policy.navigation(request.getUrl().toString());
            if (!allow && request.isForMainFrame()) stopPage("Переход за пределы исходного объявления остановлен. Откройте оригинал для работы с площадкой.");
            return !allow;
        }

        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (stopped) return blocked();
            String url = request.getUrl().toString(), accept = "";
            for (Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
                if (header.getKey().equalsIgnoreCase("Authorization")) return blocked();
                if (header.getKey().equalsIgnoreCase("Accept")) accept = header.getValue();
            }
            boolean deny = request.isForMainFrame()
                ? !"GET".equals(request.getMethod()) || !policy.navigation(url)
                : policy.blockResource(url, request.getMethod(), accept);
            return deny ? blocked() : null;
        }

        @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
            importButton.setEnabled(false);
            if (!policy.navigation(url)) stopPage("Адрес исходного объявления изменился. Импорт остановлен.");
        }

        @Override public void onPageFinished(WebView view, String url) {
            if (!stopped && policy.navigation(url) && SystemClock.elapsedRealtime() < deadline) {
                status.setText("Если видны данные исходного объявления, нажмите «Импортировать». Проверки площадки и вход не выполняются.");
                importButton.setEnabled(true);
            }
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) stopPage("Не удалось открыть публичную страницу. Откройте оригинал или добавьте объект вручную.");
        }
        @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request.isForMainFrame()) stopPage("Площадка вернула HTTP " + response.getStatusCode() + ". Откройте оригинал или добавьте объект вручную.");
        }
        @Override public void onReceivedSslError(WebView view, SslErrorHandler response, SslError error) {
            response.cancel(); stopPage("Не удалось подтвердить HTTPS-соединение с площадкой.");
        }
        @Override public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler response, String host, String realm) {
            response.cancel(); stopPage("Страница требует вход. Здесь доступны только публичные объявления.");
        }
        @Override public void onReceivedClientCertRequest(WebView view, ClientCertRequest request) {
            request.cancel(); stopPage("Страница требует сертификат. Откройте оригинал.");
        }
        @Override public void onSafeBrowsingHit(WebView view, WebResourceRequest request, int threat, SafeBrowsingResponse response) {
            response.backToSafety(true); stopPage("Проверка безопасности браузера остановила загрузку.");
        }
        @Override public void onFormResubmission(WebView view, Message dontResend, Message resend) { dontResend.sendToTarget(); }
        @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            stopPage("Браузер остановился. Откройте оригинал или добавьте объект вручную."); return true;
        }
    }
}

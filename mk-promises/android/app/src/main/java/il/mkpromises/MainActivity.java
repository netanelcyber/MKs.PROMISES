package il.mkpromises;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.json.JSONObject;

/** Hosts the bundled web app in a WebView and gives it a small native bridge. */
public class MainActivity extends Activity {

    private static final String KNESSET_ODATA = "https://knesset.gov.il/OdataV4/ParliamentInfo/";

    private WebView web;
    private BillsLoader bills;

    /**
     * Quotes a string as a JavaScript string literal that is also safe inside an inline script.
     * U+2028 and U+2029 are written as numeric case labels on purpose: a raw copy of either
     * character in this file is a line terminator to javac and breaks the build.
     */
    private static String jsq(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '"': b.append("\\\""); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '<': b.append("\\u003c"); break;
                case 0x2028: b.append("\\u2028"); break;
                case 0x2029: b.append("\\u2029"); break;
                default: b.append(c);
            }
        }
        return b.append('"').toString();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);

        // The $skip loop over KNS_Bill runs in Java; each finished page is reported to the page.
        bills = new BillsLoader(new File(getFilesDir(), "bills"), KNESSET_ODATA, (state, skip, msg) ->
                runOnUiThread(() -> web.evaluateJavascript(
                        "window.__billsNative&&window.__billsNative(" + jsq(state) + "," + skip + "," + jsq(msg) + ")", null)));

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                    return true;
                }
                return false;
            }
        });
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.__back && window.__back()", value -> {
            if (!"true".equals(value)) {
                MainActivity.super.onBackPressed();
            }
        });
    }

    /** Methods callable from the page as window.Android.*. */
    private class Bridge {

        /**
         * GET a Knesset URL natively (no CORS) and hand the body back to the page
         * as base64 through window.__nativeDone(id, ok, status, b64).
         * Only https hosts under knesset.gov.il are allowed.
         */
        @JavascriptInterface
        public void httpGet(final int id, final String url) {
            new Thread(() -> {
                int status = 0;
                boolean ok = false;
                String body = "";
                try {
                    URL u = new URL(url);
                    String host = u.getHost();
                    boolean allowed = "https".equals(u.getProtocol())
                            && (host.equals("knesset.gov.il") || host.endsWith(".knesset.gov.il"));
                    if (!allowed) {
                        throw new IOException("host not allowed");
                    }
                    HttpURLConnection c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    c.setRequestProperty("Accept", "application/json");
                    status = c.getResponseCode();
                    InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    if (in != null) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                        in.close();
                    }
                    body = out.toString("UTF-8");
                    ok = status >= 200 && status < 300;
                } catch (Exception e) {
                    body = String.valueOf(e.getMessage());
                }
                final String b64 = Base64.encodeToString(body.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                final int st = status;
                final boolean k = ok;
                runOnUiThread(() -> web.evaluateJavascript(
                        "window.__nativeDone(" + id + "," + k + "," + st + ",'" + b64 + "')", null));
            }).start();
        }

        /** Read an official committee source through the configured Selenium service. */
        @JavascriptInterface
        public void httpReadProtocol(final int id, final String url, final String token, final String json) {
            new Thread(() -> {
                int status = 0;
                boolean ok = false;
                String body = "";
                HttpURLConnection connection = null;
                try {
                    URL endpoint = new URL(url);
                    if (!"https".equals(endpoint.getProtocol()) || endpoint.getUserInfo() != null
                            || endpoint.getQuery() != null || endpoint.getRef() != null
                            || !endpoint.getPath().endsWith("/api/protocols")) {
                        throw new IOException("invalid protocol service endpoint");
                    }
                    JSONObject payload = new JSONObject(json);
                    Object requested = payload.opt("url");
                    if (payload.length() != 1 || !(requested instanceof String)
                            || ((String) requested).length() > 4096) {
                        throw new IOException("invalid protocol request");
                    }
                    URL source = new URL((String) requested);
                    String host = source.getHost().toLowerCase(Locale.ROOT);
                    if (!"https".equals(source.getProtocol()) || source.getUserInfo() != null
                            || (source.getPort() != -1 && source.getPort() != 443)
                            || !(host.equals("knesset.gov.il") || host.endsWith(".knesset.gov.il"))) {
                        throw new IOException("official HTTPS source required");
                    }
                    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > 32768 || (token != null && token.length() > 512)) {
                        throw new IOException("protocol request too large");
                    }
                    if (token != null) {
                        for (int i = 0; i < token.length(); i++) {
                            if (token.charAt(i) < 33 || token.charAt(i) > 126) {
                                throw new IOException("invalid protocol token");
                            }
                        }
                    }
                    connection = (HttpURLConnection) endpoint.openConnection();
                    connection.setRequestMethod("POST");
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(75000);
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    connection.setRequestProperty("Accept", "application/json");
                    if (token != null && !token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
                    connection.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
                    status = connection.getResponseCode();
                    InputStream response = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    if (response != null) {
                        try (InputStream in = response) {
                            byte[] buffer = new byte[8192];
                            int count;
                            while ((count = in.read(buffer)) != -1) {
                                if (out.size() + count > 524288) throw new IOException("protocol response too large");
                                out.write(buffer, 0, count);
                            }
                        }
                    }
                    body = out.toString("UTF-8");
                    ok = status >= 200 && status < 300;
                } catch (Exception e) {
                    status = 0;
                    body = "{\"error\":{\"message\":\"החיבור לשירות הפרוטוקולים נכשל או שהתשובה חרגה מהמגבלה\"}}";
                } finally {
                    if (connection != null) connection.disconnect();
                }
                final String b64 = Base64.encodeToString(body.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                final int st = status;
                final boolean success = ok;
                runOnUiThread(() -> web.evaluateJavascript(
                        "window.__protocolNativeDone && window.__protocolNativeDone(" + id + "," + success + "," + st + ",'" + b64 + "')", null));
            }).start();
        }

        /**
         * Starts (or continues) the KNS_Bill loop: $skip = 0, 100, 200, ... until an empty page.
         * Returns false if a run is already active. Progress arrives through
         * window.__billsNative(state, skip, msg).
         */
        @JavascriptInterface
        public boolean startBills(int knesset, boolean fresh) {
            return bills.start(knesset, fresh);
        }

        @JavascriptInterface
        public void stopBills() {
            bills.stop();
        }

        /** JSON describing the saved run ({knesset,nextSkip,done,loadedAt}) or an empty string. */
        @JavascriptInterface
        public String billsMeta() {
            return bills.meta();
        }

        /** The raw OData JSON of the page saved at this $skip, or an empty string. */
        @JavascriptInterface
        public String billsPage(int skip) {
            return bills.page(skip);
        }

        @JavascriptInterface
        public void wipeBills() {
            bills.wipe();
        }

        /** Opens the system share sheet with plain text (used for data export). */
        @JavascriptInterface
        public void shareText(final String text) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, null));
            });
        }
    }
}

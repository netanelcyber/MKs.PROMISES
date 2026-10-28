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
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Hosts the bundled web app in a WebView and gives it a small native bridge. */
public class MainActivity extends Activity {

    private WebView web;

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

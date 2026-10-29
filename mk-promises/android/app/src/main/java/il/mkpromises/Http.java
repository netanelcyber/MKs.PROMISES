package il.mkpromises;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Small blocking HTTP GET helper. Call it from a background thread only. */
final class Http {

    /** Decides which hosts a request (and each redirect) may reach. */
    interface HostRule {
        boolean allows(String host);
    }

    static final HostRule KNESSET = host -> {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("knesset.gov.il") || h.endsWith(".knesset.gov.il");
    };

    static final HostRule MAKOR_RISHON = host -> {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("makorrishon.co.il") || h.equals("www.makorrishon.co.il");
    };

    /** Any public internet host; loopback, LAN, link-local and unique-local addresses are refused. */
    static final HostRule PUBLIC = Http::isPublicHost;

    private static final int MAX_BYTES = 3 * 1024 * 1024;
    private static final int MAX_FILE_BYTES = 25 * 1024 * 1024;

    private Http() {
    }

    static boolean isPublicHost(String host) {
        if (host == null || host.isEmpty() || host.equalsIgnoreCase("localhost")) {
            return false;
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                byte[] b = a.getAddress();
                boolean uniqueLocal = b.length == 16 && (b[0] & 0xfe) == 0xfc;
                boolean cgnat = b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
                if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                        || a.isSiteLocalAddress() || a.isMulticastAddress() || uniqueLocal || cgnat) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** GET a URL and return the body. Throws HttpException for HTTP errors (the body is kept for diagnostics). */
    static String get(String url, String accept, HostRule rule) throws IOException {
        return new String(fetch(url, accept, rule, MAX_BYTES), StandardCharsets.UTF_8);
    }

    /** GET a file (up to 25 MB), for example a protocol .docx. */
    static byte[] getBytes(String url, HostRule rule) throws IOException {
        return fetch(url, "*/*", rule, MAX_FILE_BYTES);
    }

    private static byte[] fetch(String url, String accept, HostRule rule, int limit) throws IOException {
        String current = url;
        for (int hop = 0; hop < 4; hop++) {
            URL u = new URL(current);
            if (!"https".equals(u.getProtocol()) || u.getUserInfo() != null
                    || (u.getPort() != -1 && u.getPort() != 443) || !rule.allows(u.getHost())) {
                throw new IOException("כתובת לא מותרת: " + u.getHost());
            }
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("Accept", accept);
                c.setRequestProperty("Accept-Language", "he");
                c.setRequestProperty("User-Agent", "mk-promises/1.0");
                int status = c.getResponseCode();
                String location = c.getHeaderField("Location");
                if (status >= 300 && status < 400 && location != null) {
                    current = new URL(u, location).toString();
                    continue;
                }
                InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                byte[] body = read(in, limit);
                if (status < 200 || status >= 300) {
                    throw new HttpException(status, new String(body, StandardCharsets.UTF_8));
                }
                return body;
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("יותר מדי הפניות");
    }

    private static byte[] read(InputStream in, int limit) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < limit) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    static final class HttpException extends IOException {
        final int status;
        final String body;

        HttpException(int status, String body) {
            super("HTTP " + status + snippet(body));
            this.status = status;
            this.body = body;
        }

        private static String snippet(String body) {
            if (body == null) {
                return "";
            }
            String t = body.replaceAll("\\s+", " ").trim();
            return t.isEmpty() ? "" : " — " + t.substring(0, Math.min(300, t.length()));
        }
    }
}

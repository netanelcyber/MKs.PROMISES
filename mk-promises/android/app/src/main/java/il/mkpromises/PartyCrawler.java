package il.mkpromises;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Crawls one party website from a start page: follows only links on the same site that look like a platform page
 * (platform, promises, vision...), at most {@link #MAX_PAGES} pages and two levels deep, one request per second, and
 * respects robots.txt. It collects lines that look like promises and the page each came from. Blocking: background thread only.
 */
final class PartyCrawler {

    static final int MAX_PAGES = 12;
    static final int MAX_DEPTH = 2;
    private static final String[] KEYWORDS = {"מצע", "הבטחות", "הבטחה", "חזון", "עקרונות", "תוכנית", "יעדים", "פלטפורמה", "אג'נדה", "אג׳נדה",
            "platform", "promise", "vision", "program", "agenda", "principles", "manifesto", "policy"};

    static final class Found {
        final String text;
        final String url;

        Found(String text, String url) {
            this.text = text;
            this.url = url;
        }
    }

    interface Progress {
        void onPage(int number, String url);
    }

    private PartyCrawler() {
    }

    static String site(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    static List<Found> crawl(String start, Progress progress) throws IOException, InterruptedException {
        URI first = URI.create(start);
        if (first.getHost() == null) {
            throw new IOException("כתובת לא תקינה");
        }
        final String site = site(first.getHost());
        Http.HostRule rule = host -> site(host).equals(site) && Http.isPublicHost(host);
        List<String> disallowed = robots(first, rule);

        List<Found> found = new ArrayList<>();
        Set<String> texts = new HashSet<>();
        Set<String> visited = new HashSet<>();
        Deque<String[]> queue = new ArrayDeque<>();   // {url, depth}
        queue.add(new String[]{start, "0"});
        int pages = 0;
        while (!queue.isEmpty() && pages < MAX_PAGES) {
            String[] item = queue.poll();
            String url = item[0];
            int depth = Integer.parseInt(item[1]);
            String key = url.replaceAll("[?#].*$", "").replaceAll("/+$", "");
            if (!visited.add(key) || blocked(URI.create(url), disallowed)) {
                continue;
            }
            if (pages > 0) {
                Thread.sleep(1000);
            }
            String html;
            try {
                html = Http.get(url, "text/html", rule);
            } catch (IOException e) {
                if (pages == 0) {
                    throw e;   // the start page itself failed: tell the user
                }
                continue;
            }
            pages++;
            if (progress != null) {
                progress.onPage(pages, url);
            }
            for (String line : Html.promises(html)) {
                if (texts.add(line)) {
                    found.add(new Found(line, url));
                }
            }
            if (depth < MAX_DEPTH) {
                for (Map.Entry<String, String> link : Html.links(html, url).entrySet()) {
                    if (looksLikePlatform(link.getKey(), link.getValue())) {
                        queue.add(new String[]{link.getKey(), String.valueOf(depth + 1)});
                    }
                }
            }
        }
        return found;
    }

    static boolean looksLikePlatform(String url, String anchorText) {
        String hay = (url + " " + anchorText).toLowerCase(Locale.ROOT);
        for (String k : KEYWORDS) {
            if (hay.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** Disallow rules of the "User-agent: *" group. A missing or unreadable robots.txt allows everything. */
    static List<String> robots(URI start, Http.HostRule rule) {
        List<String> out = new ArrayList<>();
        try {
            String body = Http.get("https://" + start.getHost() + "/robots.txt", "text/plain", rule);
            boolean star = false;
            for (String raw : body.split("\\r?\\n")) {
                String line = raw.replaceAll("#.*$", "").trim();
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("user-agent:")) {
                    star = line.substring(11).trim().equals("*");
                } else if (star && lower.startsWith("disallow:")) {
                    String path = line.substring(9).trim();
                    if (!path.isEmpty()) {
                        out.add(path);
                    }
                }
            }
        } catch (IOException e) {
            // no robots.txt
        }
        return out;
    }

    static boolean blocked(URI u, List<String> disallowed) {
        String path = u.getPath() == null || u.getPath().isEmpty() ? "/" : u.getPath();
        for (String d : disallowed) {
            String prefix = d.endsWith("*") ? d.substring(0, d.length() - 1) : d;
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}

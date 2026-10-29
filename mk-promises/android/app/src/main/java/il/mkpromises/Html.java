package il.mkpromises;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal HTML text extraction. Output is plain text only: titles and links, never rendered markup. */
final class Html {

    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9a-fA-F]+|amp|lt|gt|quot|apos|nbsp);");
    private static final Pattern ANCHOR = Pattern.compile("<a\\s[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOCK = Pattern.compile("<(li|p|h[2-4])\\b[^>]*>([\\s\\S]*?)</\\1>", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOISE = Pattern.compile("<(script|style|nav|footer|header)\\b[\\s\\S]*?</\\1>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEBREW = Pattern.compile("[א-ת]");

    private Html() {
    }

    static String text(String html) {
        String s = html.replaceAll("<[^>]*>", " ");
        Matcher m = ENTITY.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String e = m.group(1).toLowerCase();
            String r;
            switch (e) {
                case "amp": r = "&"; break;
                case "lt": r = "<"; break;
                case "gt": r = ">"; break;
                case "quot": r = "\""; break;
                case "apos": r = "'"; break;
                case "nbsp": r = " "; break;
                default:
                    try {
                        int n = e.charAt(1) == 'x' ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1));
                        r = n > 31 && n < 0x10ffff && !(n >= 0xd800 && n <= 0xdfff) ? new String(Character.toChars(n)) : " ";
                    } catch (NumberFormatException ex) {
                        r = " ";
                    }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(r));
        }
        m.appendTail(out);
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    /** Same-site article links: title and URL, no query or fragment, menu/tag/author links dropped. */
    static Map<String, String> articles(String html, String base) {
        Map<String, String> out = new LinkedHashMap<>();   // url -> title
        Matcher m = ANCHOR.matcher(html);
        String baseHost = URI.create(base).getHost();
        while (m.find() && out.size() < 30) {
            URI u;
            try {
                u = URI.create(base).resolve(text(m.group(1)).replace(" ", "%20"));
            } catch (Exception e) {
                continue;
            }
            String path = u.getPath() == null ? "" : u.getPath().replaceAll("/+$", "");
            if (!"https".equals(u.getScheme()) || u.getHost() == null || !u.getHost().equalsIgnoreCase(baseHost)
                    || !path.matches("^/[^/]+/.+") || path.matches("(?i)^/(tag|category|author|page|feed|wp-).*")) {
                continue;
            }
            String title = text(m.group(2));
            if (title.length() < 12 || title.length() > 200) {
                continue;
            }
            String url = "https://" + u.getHost() + u.getPath();
            if (!out.containsKey(url)) {
                out.put(url, title);
            }
        }
        return out;
    }

    /** Same-host https links of a page: url (no query or fragment) -> anchor text. */
    static Map<String, String> links(String html, String pageUrl) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = ANCHOR.matcher(html);
        URI base = URI.create(pageUrl);
        while (m.find() && out.size() < 300) {
            URI u;
            try {
                u = base.resolve(text(m.group(1)).replace(" ", "%20"));
            } catch (Exception e) {
                continue;
            }
            if (!"https".equals(u.getScheme()) || u.getHost() == null || !u.getHost().equalsIgnoreCase(base.getHost())) {
                continue;
            }
            String url = "https://" + u.getHost() + (u.getPath() == null ? "" : u.getPath());
            if (!out.containsKey(url)) {
                out.put(url, text(m.group(2)));
            }
        }
        return out;
    }

    /** Lines of a page that look like promises: Hebrew list items, paragraphs and headings of 20-260 characters. */
    static List<String> promises(String html) {
        List<String> out = new ArrayList<>();
        Matcher m = BLOCK.matcher(NOISE.matcher(html).replaceAll(" "));
        while (m.find() && out.size() < 80) {
            String t = text(m.group(2));
            int hebrew = 0;
            Matcher h = HEBREW.matcher(t);
            while (h.find()) {
                hebrew++;
            }
            if (t.length() >= 20 && t.length() <= 260 && hebrew >= 15 && !out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }
}

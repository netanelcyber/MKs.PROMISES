package il.mkpromises;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Committee protocol text: extraction from .docx or plain text, and a split into per-speaker utterances (port of ml/protocol_parser.py). */
final class Protocol {

    static final class Utterance {
        final String speakerRaw;
        final String speaker;
        final String text;

        Utterance(String speakerRaw, String speaker, String text) {
            this.speakerRaw = speakerRaw;
            this.speaker = speaker;
            this.text = text;
        }
    }

    private static final Pattern TAG = Pattern.compile("<<[^<>]*>>");
    private static final Pattern PAREN = Pattern.compile("\\([^)]*\\)");
    private static final Pattern HEADER = Pattern.compile("^([^:\\n]{2,80}?)\\s*:$");
    private static final String[] TITLES = {"חבר הכנסת", "חברת הכנסת", "סגנית השר", "סגן השר", "היו\"ר", "יו\"ר", "חה\"כ", "ח\"כ", "השרה",
            "פרופ'", "מנכ\"לית", "מנכ\"ל", "גברת", "עו\"ד", "רו\"ח", "ד\"ר", "גב'", "השר", "מר"};
    private static final Set<String> NOT_SPEAKERS = new HashSet<>(Arrays.asList("סדר היום", "נכחו", "חברי הוועדה", "חברי הכנסת", "מוזמנים",
            "נוכחים", "רישום פרלמנטרי", "רשמת פרלמנטרית", "מנהל הוועדה", "הצעת החלטה"));

    private Protocol() {
    }

    /** Text of a protocol file. Supports .docx and plain text. .doc and PDF need converting first. */
    static String text(byte[] data) throws IOException {
        if (data.length > 4 && data[0] == 'P' && data[1] == 'K') {
            return docx(data);
        }
        if (data.length > 4 && data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F') {
            throw new IOException("קובץ PDF אינו נתמך. המירו לטקסט או ל-docx");
        }
        if (data.length > 4 && (data[0] & 0xff) == 0xd0 && (data[1] & 0xff) == 0xcf) {
            throw new IOException("קובץ doc ישן אינו נתמך. שמרו אותו כ-docx או כטקסט");
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static String docx(byte[] data) throws IOException {
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data));
        try {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (!e.getName().equals("word/document.xml")) {
                    continue;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = zip.read(buf)) > 0 && out.size() < 40 * 1024 * 1024) {
                    out.write(buf, 0, n);
                }
                String xml = new String(out.toByteArray(), StandardCharsets.UTF_8);
                StringBuilder text = new StringBuilder();
                Matcher p = Pattern.compile("<w:p[ >][\\s\\S]*?</w:p>").matcher(xml);
                Pattern t = Pattern.compile("<w:t(?: [^>]*)?>([\\s\\S]*?)</w:t>");
                while (p.find()) {
                    Matcher m = t.matcher(p.group());
                    while (m.find()) {
                        text.append(Html.text(m.group(1)));
                    }
                    text.append('\n');
                }
                return text.toString();
            }
        } finally {
            zip.close();
        }
        throw new IOException("קובץ docx ללא תוכן");
    }

    private static String norm(String s) {
        return s.replace('״', '"').replace('”', '"').replace('“', '"').replace('׳', '\'').replace('’', '\'').replace('‘', '\'')
                .trim().replaceAll("\\s+", " ");
    }

    static String cleanSpeaker(String raw) {
        String s = norm(PAREN.matcher(TAG.matcher(raw).replaceAll("")).replaceAll(" "));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String t : TITLES) {
                if (s.startsWith(t + " ") || s.equals(t)) {
                    s = s.substring(t.length()).trim();
                    changed = true;
                }
            }
        }
        return s.replaceAll("^[ \\-–,]+|[ \\-–,]+$", "");
    }

    private static String header(String line) {
        Matcher m = HEADER.matcher(line);
        if (!m.matches()) {
            return null;
        }
        String name = m.group(1).trim();
        if (NOT_SPEAKERS.contains(norm(name)) || Character.isDigit(name.charAt(0)) || name.split(" ").length > 9
                || name.matches(".*[.?!;].*")) {
            return null;
        }
        return name;
    }

    static List<Utterance> parse(String text) {
        List<Utterance> out = new ArrayList<>();
        String cur = null;
        StringBuilder buf = new StringBuilder();
        for (String raw : (text + "\n").split("\\r?\\n")) {
            String line = TAG.matcher(raw).replaceAll("").trim();
            if (line.isEmpty()) {
                continue;
            }
            String name = header(line);
            if (name != null) {
                flush(out, cur, buf);
                cur = name;
                buf = new StringBuilder();
            } else if (cur != null) {
                buf.append(' ').append(line);
            }
        }
        flush(out, cur, buf);
        return out;
    }

    private static void flush(List<Utterance> out, String cur, StringBuilder buf) {
        if (cur == null) {
            return;
        }
        String body = buf.toString().trim().replaceAll("\\s+", " ");
        if (!body.isEmpty()) {
            String raw = norm(cur);
            out.add(new Utterance(raw, cleanSpeaker(raw), body));
        }
    }

    /** True when the speaker is the member: same words, or one name's words contained in the other's (at least two words each). */
    static boolean sameSpeaker(String speaker, String memberName) {
        Set<String> a = new HashSet<>(Arrays.asList(norm(speaker).split(" ")));
        Set<String> b = new HashSet<>(Arrays.asList(norm(memberName).split(" ")));
        if (norm(speaker).equals(norm(memberName))) {
            return true;
        }
        return a.size() >= 2 && b.size() >= 2 && (a.containsAll(b) || b.containsAll(a));
    }

    /** Pieces of at most about maxWords words, split at sentence ends. */
    static List<String> chunks(String text, int maxWords) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int n = 0;
        for (String s : text.split("(?<=[.?!])\\s+")) {
            String t = s.trim();
            if (t.isEmpty()) {
                continue;
            }
            int w = t.split(" ").length;
            if (n > 0 && n + w > maxWords) {
                out.add(cur.toString().trim());
                cur = new StringBuilder();
                n = 0;
            }
            cur.append(t).append(' ');
            n += w;
        }
        if (n > 0) {
            out.add(cur.toString().trim());
        }
        return out;
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}

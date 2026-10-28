package il.mkpromises;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Downloads KNS_PlenumVote with a $skip loop on a background thread, storing every raw page
 * on disk. Same pattern as {@link BillsLoader} but for plenum votes (הצבעות במליאה).
 */
final class VotesLoader {

    interface Listener {
        void onEvent(String state, int skip, String msg);
    }

    private static final int PAGE = 100;
    private static final int MAX_SKIP = 300000;
    private static final int TRIES = 3;
    private static final Pattern HAS_VALUE = Pattern.compile("\"value\"\\s*:\\s*\\[");
    private static final Pattern EMPTY = Pattern.compile("\"value\"\\s*:\\s*\\[\\s*\\]");

    private static final class HttpStatusException extends IOException {
        final int status;

        HttpStatusException(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }

    private static final class Meta {
        int knesset;
        int nextSkip;
        boolean done;
        String loadedAt = "";
        boolean noOrder;
    }

    private final File dir;
    private final String base;
    private final Listener listener;
    private final long delayMs;
    private volatile boolean stop;
    private Thread worker;

    VotesLoader(File dir, String base, Listener listener) {
        this(dir, base, listener, 120);
    }

    VotesLoader(File dir, String base, Listener listener, long delayMs) {
        this.dir = dir;
        this.base = base;
        this.listener = listener;
        this.delayMs = delayMs;
    }

    synchronized boolean start(final int knesset, boolean fresh) {
        if (worker != null && worker.isAlive()) {
            return false;
        }
        Meta m = readMeta();
        if (fresh || m == null || m.knesset != knesset) {
            wipeFiles();
            m = new Meta();
            m.knesset = knesset;
        }
        m.done = false;
        final Meta meta = m;
        stop = false;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop(meta);
            }
        }, "votes-loader");
        worker.start();
        return true;
    }

    void stop() {
        stop = true;
    }

    synchronized void wipe() {
        stop = true;
        if (worker != null) {
            try {
                worker.join(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        wipeFiles();
    }

    synchronized String meta() {
        Meta m = readMeta();
        if (m == null) {
            return "";
        }
        return "{\"knesset\":" + m.knesset + ",\"nextSkip\":" + m.nextSkip + ",\"done\":" + m.done
                + ",\"loadedAt\":\"" + m.loadedAt + "\",\"noOrder\":" + m.noOrder + "}";
    }

    String page(int skip) {
        File f = new File(dir, "p" + skip + ".json");
        try {
            return f.isFile() ? readFile(f) : "";
        } catch (IOException e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- the loop

    private void loop(Meta m) {
        try {
            while (true) {
                if (stop) {
                    finish(m, "stopped", "");
                    return;
                }
                if (m.nextSkip >= MAX_SKIP) {
                    finish(m, "stopped", "safety stop at " + MAX_SKIP + " rows");
                    return;
                }
                String body;
                try {
                    body = fetchPage(m);
                } catch (HttpStatusException e) {
                    if (!m.noOrder && m.nextSkip == 0 && e.status >= 400 && e.status < 500) {
                        m.noOrder = true;
                        writeMeta(m);
                        continue;
                    }
                    throw e;
                }
                if (!HAS_VALUE.matcher(body).find()) {
                    throw new IOException("unexpected response (not OData JSON)");
                }
                if (EMPTY.matcher(body).find()) {
                    m.done = true;
                    finish(m, "done", "");
                    return;
                }
                int skip = m.nextSkip;
                writePage(skip, body);
                m.nextSkip = skip + PAGE;
                writeMeta(m);
                listener.onEvent("progress", skip, "");
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish(m, "stopped", "");
        } catch (Exception e) {
            finish(m, "error", String.valueOf(e.getMessage()));
        }
    }

    private void finish(Meta m, String state, String msg) {
        m.loadedAt = utcNow();
        try {
            writeMeta(m);
        } catch (IOException ignored) {
        }
        listener.onEvent(state, m.nextSkip, msg);
    }

    private String fetchPage(Meta m) throws IOException, InterruptedException {
        String url = base + "KNS_PlenumVote?$skip=" + m.nextSkip + "&$top=" + PAGE
                + (m.noOrder ? "" : "&$orderby=Id")
                + (m.knesset > 0 ? "&$filter=KnessetNum%20eq%20" + m.knesset : "")
                + "&$select=Id,KnessetNum,VoteDateTime,VoteTitle,VoteSubject,VoteMethodDesc,VoteStatusDesc,ForOptionDesc,AgainstOptionDesc,IsNoConfidenceInGov";
        IOException last = null;
        for (int t = 0; t < TRIES; t++) {
            if (stop) {
                break;
            }
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("Accept", "application/json");
                int status = c.getResponseCode();
                if (status >= 200 && status < 300) {
                    InputStream in = c.getInputStream();
                    try {
                        return readAll(in);
                    } finally {
                        in.close();
                        c.disconnect();
                    }
                }
                c.disconnect();
                if (status == 429 || status >= 500) {
                    last = new HttpStatusException(status);
                } else {
                    throw new HttpStatusException(status);
                }
            } catch (HttpStatusException e) {
                if (e.status != 429 && e.status < 500) {
                    throw e;
                }
                last = e;
            } catch (IOException e) {
                last = e;
            }
            if (t < TRIES - 1 && delayMs > 0) {
                Thread.sleep(delayMs * 10 * (t + 1));
            }
        }
        throw last != null ? last : new IOException("stopped");
    }

    // ---------------------------------------------------------------- files

    private void writePage(int skip, String body) throws IOException {
        writeAtomic(new File(dir, "p" + skip + ".json"), body);
    }

    private Meta readMeta() {
        File f = new File(dir, "meta");
        if (!f.isFile()) {
            return null;
        }
        try {
            String[] p = readFile(f).trim().split("\\|", -1);
            Meta m = new Meta();
            m.knesset = Integer.parseInt(p[0]);
            m.nextSkip = Integer.parseInt(p[1]);
            m.done = "1".equals(p[2]);
            m.loadedAt = p[3];
            m.noOrder = p.length > 4 && "1".equals(p[4]);
            return m;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void writeMeta(Meta m) throws IOException {
        writeAtomic(new File(dir, "meta"), m.knesset + "|" + m.nextSkip + "|" + (m.done ? "1" : "0")
                + "|" + m.loadedAt + "|" + (m.noOrder ? "1" : "0"));
    }

    private void wipeFiles() {
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                f.delete();
            }
        }
    }

    private void writeAtomic(File target, String text) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File tmp = new File(dir, target.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        if (!tmp.renameTo(target)) {
            target.delete();
            if (!tmp.renameTo(target)) {
                throw new IOException("cannot write " + target);
            }
        }
    }

    private static String readFile(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String utcNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }
}

package il.mkpromises;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Persists Gemini topic tags for bills as a single JSON file on disk.
 * The file holds a JSON object mapping bill IDs (strings) to topic strings.
 * Thread-safe through synchronization on every public method.
 */
final class GeminiTagStore {

    private final File dir;
    private final File file;

    GeminiTagStore(File dir) {
        this.dir = dir;
        this.file = new File(dir, "tags.json");
    }

    /** Returns the full JSON object as a string, or "{}" if nothing is stored. */
    synchronized String readAll() {
        if (!file.isFile()) return "{}";
        try {
            return readFile(file);
        } catch (IOException e) {
            return "{}";
        }
    }

    /** Overwrites the entire tags file with the given JSON string. */
    synchronized void writeAll(String json) {
        try {
            writeAtomic(file, json);
        } catch (IOException ignored) {
        }
    }

    /** Deletes all stored tags. */
    synchronized void wipe() {
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
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }
}

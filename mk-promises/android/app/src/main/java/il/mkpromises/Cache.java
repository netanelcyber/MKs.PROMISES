package il.mkpromises;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Local caches of Knesset data (bills, committees). Kept apart from the backup, like in the web app. */
final class Cache {

    private Cache() {
    }

    static JSONObject read(MainActivity app, String name) {
        File f = new File(app.getFilesDir(), name);
        if (!f.exists()) {
            return null;
        }
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    static boolean write(MainActivity app, String name, JSONObject o) {
        try {
            File tmp = new File(app.getFilesDir(), name + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            File target = new File(app.getFilesDir(), name);
            return tmp.renameTo(target) || (target.delete() && tmp.renameTo(target));
        } catch (IOException e) {
            return false;
        }
    }

    static JSONArray arr(JSONObject o, String key) {
        JSONArray a = o == null ? null : o.optJSONArray(key);
        return a == null ? new JSONArray() : a;
    }
}

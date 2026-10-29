package il.mkpromises;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * The saved data: one JSON file in the app's private storage, in the same format as the web app's backup
 * ({"v":1,"mks":[{id,name,faction,knesset,isNew,apiPersonId?,promises:[...],background:[...]}]}),
 * so a backup moves between the two without conversion.
 */
final class Store {

    private final File file;
    JSONObject state;

    Store(File dir) {
        this.file = new File(dir, "state.json");
        this.state = read();
    }

    private JSONObject read() {
        try {
            if (file.exists()) {
                return validate(new JSONObject(new String(readAll(file), StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            // A damaged file is kept aside instead of being overwritten.
            file.renameTo(new File(file.getParentFile(), "state.damaged." + System.currentTimeMillis() + ".json"));
        }
        return empty();
    }

    static JSONObject empty() {
        try {
            return new JSONObject().put("v", 1).put("mks", new JSONArray());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Throws when the JSON is not a usable state; fills missing arrays so the UI can rely on them. */
    static JSONObject validate(JSONObject o) throws JSONException {
        JSONArray mks = o.optJSONArray("mks");
        if (mks == null) {
            throw new JSONException("חסר mks");
        }
        for (int i = 0; i < mks.length(); i++) {
            JSONObject m = mks.getJSONObject(i);
            if (m.optString("name", "").trim().isEmpty()) {
                throw new JSONException("שם חסר");
            }
            if (m.optString("id", "").isEmpty()) {
                m.put("id", uid());
            }
            if (m.optJSONArray("promises") == null) {
                m.put("promises", new JSONArray());
            }
            if (m.optJSONArray("background") == null) {
                m.put("background", new JSONArray());
            }
            JSONArray ps = m.getJSONArray("promises");
            for (int j = 0; j < ps.length(); j++) {
                JSONObject p = ps.getJSONObject(j);
                if (p.optString("id", "").isEmpty()) {
                    p.put("id", uid());
                }
                if (p.optJSONArray("evidence") == null) {
                    p.put("evidence", new JSONArray());
                }
            }
        }
        if (!o.has("v")) {
            o.put("v", 1);
        }
        return o;
    }

    boolean save() {
        return write(state);
    }

    boolean write(JSONObject o) {
        try {
            File tmp = new File(file.getParentFile(), "state.json.tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } finally {
                out.close();
            }
            return tmp.renameTo(file) || (file.delete() && tmp.renameTo(file));
        } catch (IOException e) {
            return false;
        }
    }

    JSONArray mks() {
        return state.optJSONArray("mks");
    }

    JSONObject mk(String id) {
        JSONArray a = mks();
        for (int i = 0; i < a.length(); i++) {
            JSONObject m = a.optJSONObject(i);
            if (m != null && id.equals(m.optString("id"))) {
                return m;
            }
        }
        return null;
    }

    static String uid() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        Random r = new Random();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            b.append(chars.charAt(r.nextInt(chars.length())));
        }
        return b.toString();
    }

    private static byte[] readAll(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}

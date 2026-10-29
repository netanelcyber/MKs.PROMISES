package il.mkpromises;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** JSON helpers that do not throw checked exceptions. */
final class J {

    private J() {
    }

    static JSONObject obj(Object... keyValues) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            put(o, (String) keyValues[i], keyValues[i + 1]);
        }
        return o;
    }

    static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static JSONObject at(JSONArray a, int i) {
        JSONObject o = a.optJSONObject(i);
        return o == null ? new JSONObject() : o;
    }

    static int indexOf(JSONArray a, String key, String value) {
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && value.equals(o.optString(key))) {
                return i;
            }
        }
        return -1;
    }

    static JSONObject find(JSONArray a, String key, String value) {
        int i = indexOf(a, key, value);
        return i < 0 ? null : a.optJSONObject(i);
    }

    static JSONArray array(JSONObject o, String key) {
        JSONArray a = o.optJSONArray(key);
        if (a == null) {
            a = new JSONArray();
            put(o, key, a);
        }
        return a;
    }
}

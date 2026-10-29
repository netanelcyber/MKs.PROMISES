package il.mkpromises;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Labels (promise, bill title) pairs with the Gemini API (the user's own key), then trains the local model on them.
 * Only the promise text and the bill TITLE are sent, never notes, sources or evidence. Labels are saved in
 * gemini-labels.json (device only). A label is a training signal; it never changes a promise status.
 */
final class GeminiLabel {

    private static final String HOST = "generativelanguage.googleapis.com";
    static final String FILE = "gemini-labels.json";
    private static final int BATCH = 10;
    /** Gemini labels only this many pairs; after that the local model continues alone. */
    static final int FIRST_STEP = 20;

    private GeminiLabel() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("gemini", Context.MODE_PRIVATE);
    }

    static void start(MainActivity app) {
        SharedPreferences p = prefs(app);
        List<Ui.Field> f = new ArrayList<>();
        f.add(new Ui.Field("key", "מפתח Gemini API (נשמר רק במכשיר)", Ui.TEXT).required());
        f.add(new Ui.Field("model", "דגם", Ui.TEXT).value(p.getString("model", "gemini-2.5-flash")).required());
        Ui.form(app, "תיוג עם Gemini",
                "שלב ראשון: Gemini מתייג רק 20 זוגות ואז מתנתק (המפתח לא נשמר), והמודל המקומי ממשיך לתייג ולהתאמן לבד. נשלחים ל-Google רק נוסח ההבטחה ושם הצעת החוק. המפתח הוא שלך והשימוש נספר בחשבונך. התיוג של Gemini אינו אמת: זהו אות לאימון, ואינו משנה סטטוס.",
                f, "תיוג ואימון", v -> {
                    final int n = FIRST_STEP;
                    final String key = v.get("key").trim();
                    final String model = v.get("model").trim();
                    if (!model.matches("[A-Za-z0-9._-]{3,60}")) {
                        Ui.toast(app, "שם דגם לא תקין");
                        return;
                    }
                    p.edit().putString("model", model).remove("key").apply();   // the key is never kept: Gemini is used for this first step only
                    run(app, key, model, n);
                });
    }

    private static void run(MainActivity app, String key, String model, int n) {
        Ui.toast(app, "מתייג עם Gemini…");
        app.background(() -> {
            JSONArray bills = Cache.arr(Cache.read(app, "bills-25.json"), "rows");
            if (bills.length() == 0) {
                bills = BillsScreen.fetchAll(25, c -> {
                });
                Cache.write(app, "bills-25.json", J.obj("knesset", 25, "rows", bills));
            }
            JSONArray saved = Cache.arr(Cache.read(app, FILE), "rows");
            java.util.Set<String> done = new java.util.HashSet<>();
            for (int i = 0; i < saved.length(); i++) {
                done.add(saved.optJSONObject(i).optString("k"));
            }
            List<String[]> pairs = AutoTrain.candidates(app.store.mks(), bills, n, done);
            int added = 0;
            for (int i = 0; i < pairs.size(); i += BATCH) {
                List<String[]> chunk = pairs.subList(i, Math.min(pairs.size(), i + BATCH));
                int[] labels = ask(key, model, chunk);
                for (int j = 0; j < chunk.size(); j++) {
                    if (labels[j] >= 0) {
                        String[] c = chunk.get(j);
                        saved.put(J.obj("k", c[0], "p", c[1], "u", c[2], "l", labels[j], "g", c[3]));
                        added++;
                    }
                }
                Cache.write(app, FILE, J.obj("rows", saved));
                final int count = added;
                app.runOnUiThread(() -> Ui.toast(app, "תויגו " + count + " זוגות…"));
            }
            return added;
        }, added -> {
            Ui.toast(app, "Gemini תייג " + added + " זוגות ונותק. המודל ממשיך לבד…");
            AutoTrain.run(app);
        }, e -> Ui.toastLong(app, "התיוג נכשל: " + MainActivity.message(e)));
    }

    /** Saved Gemini labels as training rows. */
    static List<Stance.Row> rows(MainActivity app) {
        List<Stance.Row> out = new ArrayList<>();
        JSONArray a = Cache.arr(Cache.read(app, FILE), "rows");
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            int l = r.optInt("l", -1);
            if (l >= 0 && l < 3) {
                out.add(new Stance.Row(r.optString("p"), r.optString("u"), l, r.optString("g")));
            }
        }
        return out;
    }

    private static int[] ask(String key, String model, List<String[]> chunk) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("For each numbered pair, a politician's election promise and the title of a Knesset bill, decide the bill's relation to the promise: ")
                .append("\"support\" (the bill advances the promise), \"contradict\" (the bill works against it), ")
                .append("\"context\" (related topic only, or unrelated), or \"unclear\". Judge only from the two texts. ")
                .append("Answer with a JSON array of exactly ").append(chunk.size()).append(" strings in order.\n");
        for (int i = 0; i < chunk.size(); i++) {
            sb.append(i + 1).append(". Promise: ").append(chunk.get(i)[1]).append("\n   Bill: ").append(chunk.get(i)[2]).append("\n");
        }
        JSONObject body = new JSONObject();
        body.put("contents", new JSONArray().put(new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", sb.toString())))));
        body.put("generationConfig", new JSONObject().put("responseMimeType", "application/json").put("temperature", 0));
        URL url = new URL("https://" + HOST + "/v1beta/models/" + URLEncoder.encode(model, "UTF-8") + ":generateContent");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("x-goog-api-key", key);
        try (OutputStream o = c.getOutputStream()) {
            o.write(body.toString().getBytes("UTF-8"));
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while (in != null && (r = in.read(buf)) > 0 && bo.size() < 2_000_000) {
            bo.write(buf, 0, r);
        }
        String text = bo.toString("UTF-8");
        if (code != 200) {
            throw new java.io.IOException("Gemini החזיר " + code + (code == 400 || code == 403 ? " (בדקו מפתח ודגם)" : code == 429 ? " (חריגה ממכסה)" : ""));
        }
        String out = new JSONObject(text).getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0).getString("text");
        JSONArray arr = new JSONArray(out.trim());
        int[] labels = new int[chunk.size()];
        for (int i = 0; i < labels.length; i++) {
            String s = i < arr.length() ? arr.optString(i).toLowerCase(java.util.Locale.ROOT) : "";
            labels[i] = s.equals("support") ? 0 : s.equals("contradict") ? 1 : s.equals("context") ? 2 : -1;
        }
        return labels;
    }
}

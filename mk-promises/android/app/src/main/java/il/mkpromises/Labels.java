package il.mkpromises;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Exports the user's evidence labels (promise, quote, effect) as JSONL for ml/train.py. */
final class Labels {

    private Labels() {
    }

    /** Labelled pairs of the 25th Knesset members: evidence with a chosen effect. */
    static List<Stance.Row> rows(JSONArray mks) {
        List<Stance.Row> out = new ArrayList<>();
        for (int i = 0; i < mks.length(); i++) {
            JSONObject m = mks.optJSONObject(i);
            if (m == null || !"25".equals(m.optString("knesset"))) {
                continue;
            }
            JSONArray ps = m.optJSONArray("promises");
            for (int j = 0; ps != null && j < ps.length(); j++) {
                JSONObject p = ps.optJSONObject(j);
                JSONArray ev = p == null ? null : p.optJSONArray("evidence");
                for (int k = 0; ev != null && k < ev.length(); k++) {
                    JSONObject e = ev.optJSONObject(k);
                    if (e == null) {
                        continue;
                    }
                    int label = e.optString("effect").equals("support") ? 0 : e.optString("effect").equals("contradict") ? 1
                            : e.optString("effect").equals("context") ? 2 : -1;
                    String text = e.optString("excerpt", "");
                    if (text.isEmpty()) {
                        text = e.optString("title", "");
                    }
                    if (label >= 0 && !text.isEmpty() && !p.optString("text").isEmpty()) {
                        out.add(new Stance.Row(p.optString("text"), text, label, m.optString("name")));
                    }
                }
            }
        }
        return out;
    }

    static void export(MainActivity app) {
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (Stance.Row r : rows(app.store.mks())) {
            out.append(J.obj("promise", r.promise, "utterance", r.utterance, "label", Stance.LABELS[r.label], "group", r.group)).append('\n');
            n++;
        }
        if (n == 0) {
            Ui.toast(app, "אין עדיין ראיות מתויגות של חברי הכנסת ה-25");
            return;
        }
        Backup.share(app, out.toString());
        Ui.toast(app, "יוצאו " + n + " תיוגים");
    }
}

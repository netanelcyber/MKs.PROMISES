package il.mkpromises;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * One-button training on all bills of a Knesset. Bills carry no stance label, so the labels are WEAK:
 * a promise and a bill that share content words and the bill is attached to the same MK = support;
 * the same overlap with a bill of someone else = context; a bill with no overlap = context.
 * "contradict" can only come from the user's own manual labels. Manual labels are always included.
 */
final class AutoTrain {

    private static final int KNESSET = 25;
    private static final int MAX_WEAK = 4000;

    private AutoTrain() {
    }

    static void run(MainActivity app) {
        Ui.toast(app, "טוען הצעות חוק ומאמן… זה יכול לקחת כמה דקות");
        app.background(() -> {
            JSONObject cached = Cache.read(app, "bills-" + KNESSET + ".json");
            JSONArray bills = Cache.arr(cached, "rows");
            if (bills.length() == 0) {
                bills = BillsScreen.fetchAll(KNESSET, n -> app.runOnUiThread(() -> Ui.toast(app, "נטענו " + n + " הצעות…")));
                Cache.write(app, "bills-" + KNESSET + ".json", J.obj("knesset", KNESSET, "rows", bills));
            }
            int[] weak = new int[1];
            List<Stance.Row> rows = build(app.store.mks(), bills, weak);
            rows.addAll(GeminiLabel.rows(app));
            int[] seen = new int[3];
            for (Stance.Row r : rows) {
                seen[r.label]++;
            }
            if (rows.size() < 30 || seen[0] == 0 || seen[2] == 0) {
                throw new IllegalStateException("אין מספיק דוגמאות (" + rows.size() + "). שייכו הצעות חוק לחברי כנסת והוסיפו הבטחות");
            }
            Stance.Result r = Stance.train(rows, 25, (epoch, loss, acc) ->
                    app.runOnUiThread(() -> Ui.toast(app, "סבב " + epoch + " · דיוק " + Math.round(acc * 100) + "%")), 1L);
            Stance.set(app, r.model);
            return new Object[]{r, rows.size(), weak[0], seen[1]};
        }, out -> {
            Stance.Result r = (Stance.Result) out[0];
            Ui.toastLong(app, "המודל אומן ופעיל: " + out[1] + " דוגמאות (" + out[2] + " מהן חלשות מהצעות חוק, "
                    + out[3] + " עם 'סתירה' מתיוג ידני). דיוק בולידציה " + Math.round(r.accuracy * 100) + "%, macro-F1 "
                    + Math.round(r.macroF1 * 100) + "%. התיוג חלש, לכן ההצעות אינן אמינות כמו תיוג ידני.");
        }, e -> Ui.toastLong(app, "האימון נכשל: " + MainActivity.message(e)));
    }

    static List<Stance.Row> build(JSONArray mks, JSONArray bills, int[] weakCount) {
        List<Stance.Row> rows = new ArrayList<>(Labels.rows(mks));
        Map<String, List<Integer>> index = new HashMap<>();
        List<Set<String>> billTokens = new ArrayList<>();
        for (int i = 0; i < bills.length(); i++) {
            Set<String> t = content(bills.optJSONObject(i).optString("n"));
            billTokens.add(t);
            for (String w : t) {
                index.computeIfAbsent(w, x -> new ArrayList<>()).add(i);
            }
        }
        Random rng = new Random(7);
        int weak = 0;
        for (int mi = 0; mi < mks.length() && weak < MAX_WEAK; mi++) {
            JSONObject m = mks.optJSONObject(mi);
            Set<Long> own = new HashSet<>();
            JSONArray ab = m.optJSONArray("bills");
            for (int i = 0; ab != null && i < ab.length(); i++) {
                own.add(ab.optJSONObject(i).optLong("billId"));
            }
            JSONArray ps = m.optJSONArray("promises");
            for (int pi = 0; ps != null && pi < ps.length() && weak < MAX_WEAK; pi++) {
                JSONObject p = ps.optJSONObject(pi);
                String text = p.optString("text");
                Set<String> pt = content(text);
                if (pt.size() < 2) {
                    continue;
                }
                Map<Integer, Integer> hits = new HashMap<>();
                for (String w : pt) {
                    List<Integer> l = index.get(w);
                    for (int k = 0; l != null && k < l.size() && k < 2000; k++) {
                        hits.merge(l.get(k), 1, Integer::sum);
                    }
                }
                // Own bills that overlap are support; the strongest foreign overlaps are context.
                int sup = 0;
                int ctx = 0;
                List<Map.Entry<Integer, Integer>> sorted = new ArrayList<>(hits.entrySet());
                sorted.sort((a, b) -> b.getValue() - a.getValue());
                for (Map.Entry<Integer, Integer> e : sorted) {
                    if (e.getValue() < 2 || (sup >= 3 && ctx >= 3)) {
                        break;
                    }
                    JSONObject b = bills.optJSONObject(e.getKey());
                    boolean mine = own.contains(b.optLong("i"));
                    if (mine && sup < 3) {
                        rows.add(new Stance.Row(text, b.optString("n"), 0, p.optString("id")));
                        sup++;
                        weak++;
                    } else if (!mine && ctx < 3) {
                        rows.add(new Stance.Row(text, b.optString("n"), 2, p.optString("id")));
                        ctx++;
                        weak++;
                    }
                }
                for (int tries = 0; tries < 2 && bills.length() > 0; tries++) {
                    int j = rng.nextInt(bills.length());
                    if (!hits.containsKey(j)) {
                        rows.add(new Stance.Row(text, bills.optJSONObject(j).optString("n"), 2, p.optString("id")));
                        weak++;
                    }
                }
            }
        }
        weakCount[0] = weak;
        return rows;
    }

    /** Up to n new (promise, bill) pairs with shared content words: {key, promise, bill title, promise id}. */
    static List<String[]> candidates(JSONArray mks, JSONArray bills, int n, Set<String> skip) {
        Map<String, List<Integer>> index = new HashMap<>();
        for (int i = 0; i < bills.length(); i++) {
            for (String w : content(bills.optJSONObject(i).optString("n"))) {
                index.computeIfAbsent(w, x -> new ArrayList<>()).add(i);
            }
        }
        List<String[]> out = new ArrayList<>();
        for (int mi = 0; mi < mks.length() && out.size() < n; mi++) {
            JSONArray ps = mks.optJSONObject(mi).optJSONArray("promises");
            for (int pi = 0; ps != null && pi < ps.length() && out.size() < n; pi++) {
                JSONObject p = ps.optJSONObject(pi);
                Set<String> pt = content(p.optString("text"));
                Map<Integer, Integer> hits = new HashMap<>();
                for (String w : pt) {
                    List<Integer> l = index.get(w);
                    for (int k = 0; l != null && k < l.size() && k < 2000; k++) {
                        hits.merge(l.get(k), 1, Integer::sum);
                    }
                }
                List<Map.Entry<Integer, Integer>> sorted = new ArrayList<>(hits.entrySet());
                sorted.sort((a, b) -> b.getValue() - a.getValue());
                int taken = 0;
                for (Map.Entry<Integer, Integer> e : sorted) {
                    if (e.getValue() < 2 || taken >= 3 || out.size() >= n) {
                        break;
                    }
                    JSONObject b = bills.optJSONObject(e.getKey());
                    String key = p.optString("id") + "|" + b.optLong("i");
                    if (!skip.contains(key)) {
                        out.add(new String[]{key, p.optString("text"), b.optString("n"), p.optString("id")});
                        taken++;
                    }
                }
            }
        }
        return out;
    }

    /** Content words: at least 3 letters, one-letter Hebrew prefix removed, common legal filler dropped. */
    private static Set<String> content(String s) {
        Set<String> out = new HashSet<>();
        for (String w : Stance.tokens(s)) {
            if (w.length() > 3 && "ובכלמהש".indexOf(w.charAt(0)) >= 0) {
                w = w.substring(1);
            }
            if (w.length() >= 3 && !STOP.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    private static final Set<String> STOP = new HashSet<>(java.util.Arrays.asList(
            "הצעת", "חוק", "תיקון", "תיקונים", "פרטית", "הוראת", "שעה", "התשפ", "התשפ\"ג", "התשפ\"ד", "התשפ\"ה", "התשפ\"ו",
            "של", "את", "על", "עם", "לא", "אל", "כל"));
}

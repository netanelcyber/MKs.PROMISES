package il.mkpromises;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Committee activity into the model: take a protocol (downloaded from a Knesset link or picked from the device), keep what this
 * member said, score each passage against each of the member's promises, and offer the passages the model finds relevant.
 * The user picks, sets the effect, and the passage is stored as evidence with its quote (which also becomes training data).
 */
final class CommitteeImport {

    private CommitteeImport() {
    }

    private static final class Hit {
        String passage;
        String url;
        String promiseId;
        String promiseText;
        int label;
        float confidence;
    }

    static void start(MainActivity app, JSONObject member) {
        JSONArray promises = member.optJSONArray("promises");
        if (promises.length() == 0) {
            Ui.toast(app, "קודם הוסיפו הבטחה לחבר הכנסת");
            return;
        }
        List<Ui.Field> f = new ArrayList<>();
        f.add(new Ui.Field("url", "קישור לפרוטוקול (נשמר כמקור הראיה)", Ui.URL).required());
        f.add(new Ui.Field("download", "להוריד את הקובץ מהקישור (אחרת: בחירת קובץ מהמכשיר)", Ui.CHECK).value("true"));
        Ui.form(app, "פעילות בוועדה למודל: " + member.optString("name"),
                "המודל קורא רק את מה שחבר הכנסת אמר, ומציע קטעים שקשורים להבטחותיו. הקובץ יכול להיות docx או טקסט. הורדה מותרת רק מ-knesset.gov.il.",
                f, "המשך", v -> {
                    final String url = v.get("url");
                    if (!"true".equals(v.get("download"))) {
                        app.pickBytes(bytes -> analyze(app, member, url, bytes));
                        return;
                    }
                    Ui.toast(app, "מוריד את הפרוטוקול…");
                    app.background(() -> Http.getBytes(url, Http.KNESSET), bytes -> analyze(app, member, url, bytes));
                });
    }

    /** Scores what the member said in one protocol against each promise. Returns how many of the member's speeches were found. */
    private static int scan(Stance.Model model, JSONObject member, byte[] bytes, String url, List<Hit> out) throws Exception {
        JSONArray promises = member.optJSONArray("promises");
        List<Protocol.Utterance> utterances = Protocol.parse(Protocol.text(bytes));
        int mine = 0;
        for (Protocol.Utterance u : utterances) {
            if (!Protocol.sameSpeaker(u.speaker, member.optString("name"))) {
                continue;
            }
            mine++;
            for (String passage : Protocol.chunks(u.text, 80)) {
                for (int i = 0; i < promises.length(); i++) {
                    JSONObject p = promises.optJSONObject(i);
                    float[] pr = Stance.score(model, p.optString("text"), passage);
                    int k = pr[0] >= pr[1] ? 0 : 1;
                    if (pr[k] >= 0.6f && pr[k] > pr[2]) {
                        Hit h = new Hit();
                        h.passage = passage;
                        h.url = url;
                        h.promiseId = p.optString("id");
                        h.promiseText = p.optString("text");
                        h.label = k;
                        h.confidence = pr[k];
                        out.add(h);
                    }
                }
            }
        }
        if (utterances.isEmpty()) {
            throw new Exception("לא זוהו דוברים בפרוטוקול");
        }
        return mine;
    }

    private static void analyze(MainActivity app, JSONObject member, String url, byte[] bytes) {
        Ui.toast(app, "מנתח את הפרוטוקול…");
        app.background(() -> {
            Stance.Model model = Stance.get(app);
            if (model == null) {
                throw new Exception("אין מודל טעון (תפריט \"מודל\")");
            }
            List<Hit> hits = new ArrayList<>();
            int mine = scan(model, member, bytes, url, hits);
            if (mine == 0) {
                throw new Exception("לא נמצאו דברים של " + member.optString("name") + " בפרוטוקול");
            }
            return new Object[]{top(hits), model.synthetic, mine, 1};
        }, result -> show(app, member, result));
    }

    private static List<Hit> top(List<Hit> hits) {
        java.util.Collections.sort(hits, (a, b) -> Float.compare(b.confidence, a.confidence));
        return new ArrayList<>(hits.subList(0, Math.min(40, hits.size())));
    }

    @SuppressWarnings("unchecked")
    private static void show(MainActivity app, JSONObject member, Object[] result) {
        List<Hit> hits = (List<Hit>) result[0];
        if (hits.isEmpty()) {
            Ui.toastLong(app, "המודל לא מצא קטעים רלוונטיים להבטחות (נמצאו " + result[2] + " דברי הח\"כ ב-" + result[3] + " פרוטוקולים)");
            return;
        }
        choose(app, member, hits, (Boolean) result[1]);
    }

    /** One button: find the member's committees and their latest sessions, download the protocols, and run the model on all of them. */
    static void auto(MainActivity app, JSONObject member) {
        final long pid = Odata.id(member.opt("apiPersonId"));
        if (pid < 0) {
            Ui.toast(app, "לחבר הכנסת אין מזהה API (טענו אותו מהכנסת)");
            return;
        }
        if (member.optJSONArray("promises").length() == 0) {
            Ui.toast(app, "קודם הוסיפו הבטחה לחבר הכנסת");
            return;
        }
        Ui.toast(app, "טוען פעילות בוועדות… זה יכול לקחת כמה דקות");
        app.background(() -> {
            Stance.Model model = Stance.get(app);
            if (model == null) {
                throw new Exception("אין מודל טעון (תפריט \"מודל\")");
            }
            JSONObject data = Cache.read(app, "committees.json");
            if (data == null) {
                data = CommitteesScreen.fetchData(CommitteesScreen.DEFAULT_FROM, CommitteesScreen.DEFAULT_TO);
                Cache.write(app, "committees.json", data);
            }
            String from = data.optString("from");
            String to = data.optString("to");
            java.util.Set<Long> mine = new java.util.HashSet<>();
            JSONArray roles = Cache.arr(data, "roles");
            for (int i = 0; i < roles.length(); i++) {
                JSONObject r = roles.optJSONObject(i);
                String s = r.optString("s");
                String f = r.optString("f");
                if (r.optLong("p") == pid && (s.isEmpty() || s.compareTo(to) <= 0) && (f.isEmpty() || f.compareTo(from) >= 0)) {
                    mine.add(r.optLong("c"));
                }
            }
            if (mine.isEmpty()) {
                throw new Exception("לא נמצא תפקיד בוועדה בטווח " + from + " עד " + to);
            }
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
            List<JSONObject> sessions = new ArrayList<>();
            JSONArray all = Cache.arr(data, "sessions");
            for (int i = 0; i < all.length(); i++) {
                JSONObject s = all.optJSONObject(i);
                if (mine.contains(s.optLong("c")) && s.optString("d").compareTo(today) <= 0 && !s.optString("d").isEmpty()) {
                    sessions.add(s);
                }
            }
            java.util.Collections.sort(sessions, (a, b) -> b.optString("d").compareTo(a.optString("d")));
            if (sessions.size() > MAX_SESSIONS) {
                sessions = new ArrayList<>(sessions.subList(0, MAX_SESSIONS));
            }
            List<Hit> hits = new ArrayList<>();
            int protocols = 0;
            int unsupported = 0;
            int speeches = 0;
            for (int i = 0; i < sessions.size(); i += 10) {
                List<Long> ids = new ArrayList<>();
                for (JSONObject s : sessions.subList(i, Math.min(i + 10, sessions.size()))) {
                    ids.add(s.optLong("i"));
                }
                for (JSONObject d : Odata.fetchAll("KNS_DocumentCommitteeSession", Odata.idFilter("CommitteeSessionID", ids),
                        "CommitteeSessionID,GroupTypeDesc,ApplicationDesc,FilePath", 500)) {
                    String path = d.optString("FilePath", "");
                    if (!d.optString("GroupTypeDesc", "").contains("פרוטוקול") || !path.startsWith("https://")) {
                        continue;
                    }
                    protocols++;
                    final int number = protocols;
                    app.runOnUiThread(() -> Ui.toast(app, "מנתח פרוטוקול " + number + "…"));
                    Thread.sleep(700);
                    try {
                        speeches += scan(model, member, Http.getBytes(path, Http.KNESSET), path, hits);
                    } catch (java.io.IOException e) {
                        unsupported++;   // .doc or PDF, or a failed download
                    } catch (Exception e) {
                        // a protocol without recognizable speakers
                    }
                }
            }
            if (protocols == 0) {
                throw new Exception("לא נמצאו פרוטוקולים לישיבות האחרונות של הוועדות שלו");
            }
            return new Object[]{top(hits), model.synthetic, speeches, protocols - unsupported};
        }, result -> show(app, member, result));
    }

    private static final int MAX_SESSIONS = 12;

    private static void choose(MainActivity app, JSONObject member, List<Hit> hits, boolean synthetic) {
        List<Ui.Field> f = new ArrayList<>();
        f.add(Vocab.select(new Ui.Field("effect", "השפעה (נקבעת על ידך, לא על ידי המודל)", Ui.SELECT).value("context"), Vocab.EFFECT));
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            String tag = (synthetic ? "דמו לא אמין · " : "") + (h.label == 0 ? "תומך " : "סותר ") + Math.round(h.confidence * 100) + "%";
            f.add(new Ui.Field("a" + i, "[" + tag + " · " + h.promiseText.substring(0, Math.min(25, h.promiseText.length())) + "] " + h.passage, Ui.CHECK));
        }
        Ui.form(app, "קטעים מהפרוטוקול", "סמנו קטעים שבדקתם בפרוטוקול עצמו. הם יישמרו כראיה מסוג ועדה עם הציטוט.", f, "שמירה", v -> {
            JSONArray promises = member.optJSONArray("promises");
            int added = 0;
            for (int i = 0; i < hits.size(); i++) {
                if (!"true".equals(v.get("a" + i))) {
                    continue;
                }
                Hit h = hits.get(i);
                JSONObject p = J.find(promises, "id", h.promiseId);
                if (p == null) {
                    continue;
                }
                JSONArray ev = J.array(p, "evidence");
                boolean exists = false;
                for (int k = 0; k < ev.length(); k++) {
                    exists |= h.passage.equals(ev.optJSONObject(k).optString("excerpt"));
                }
                if (exists) {
                    continue;
                }
                ev.put(J.obj("id", Store.uid(), "title", "פרוטוקול ועדה: " + h.passage.substring(0, Math.min(60, h.passage.length())),
                        "type", "ועדה", "effect", v.get("effect"), "date", "", "url", h.url, "excerpt", h.passage));
                added++;
            }
            app.save();
            app.refresh();
            Ui.toast(app, added > 0 ? "נוספו " + added + " ראיות. הן נכללות גם בנתוני האימון" : "לא נבחרו קטעים חדשים");
        });
    }
}

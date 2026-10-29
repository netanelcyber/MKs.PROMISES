package il.mkpromises;

import android.view.View;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Committees of the 25th Knesset: sessions in a date window and members with their roles. */
final class CommitteesScreen implements Screen {

    private static final String FILE = "committees.json";
    private static final String DEFAULT_FROM = "2026-04-27";
    private static final String DEFAULT_TO = "2026-10-27";

    private JSONObject data;
    private boolean loading;
    private long openCommittee = -1;

    @Override
    public String title() {
        return openCommittee < 0 ? "ועדות הכנסת ה-25" : "ועדה";
    }

    @Override
    public View build(MainActivity app) {
        if (data == null) {
            data = Cache.read(app, FILE);
        }
        LinearLayout root = Ui.vbox(app);
        if (openCommittee >= 0) {
            return detail(app, root);
        }
        String from = data == null ? DEFAULT_FROM : data.optString("from", DEFAULT_FROM);
        String to = data == null ? DEFAULT_TO : data.optString("to", DEFAULT_TO);
        LinearLayout card = Ui.card(app);
        card.addView(Ui.mute(app, "טווח: " + from + " עד " + to));
        card.addView(Ui.mute(app, "הכנסת ה-25 פעלה בצל המלחמה מאז 7.10.2023."));
        LinearLayout actions = Ui.row(app);
        actions.addView(Ui.button(app, data == null ? "טעינה מהכנסת" : "רענון מהכנסת", v -> load(app, from, to)));
        actions.addView(Ui.button(app, "שינוי טווח", v -> window(app, from, to)));
        card.addView(actions);
        if (loading) {
            card.addView(Ui.mute(app, "טוען… זה יכול לקחת דקה"));
        }
        root.addView(card);
        if (data == null) {
            root.addView(Ui.mute(app, "הנתונים נטענים מה-API הרשמי של הכנסת (מחיבור ישראלי) ונשמרים במכשיר בנפרד מהגיבוי."), Ui.match(app, 8));
            return root;
        }
        final Map<Long, Integer> counts = new HashMap<>();
        JSONArray sessions = Cache.arr(data, "sessions");
        for (int i = 0; i < sessions.length(); i++) {
            long c = sessions.optJSONObject(i).optLong("c");
            counts.put(c, counts.containsKey(c) ? counts.get(c) + 1 : 1);
        }
        List<JSONObject> committees = new ArrayList<>();
        JSONArray all = Cache.arr(data, "committees");
        for (int i = 0; i < all.length(); i++) {
            committees.add(all.optJSONObject(i));
        }
        java.util.Collections.sort(committees, (a, b) -> {
            int ca = counts.containsKey(a.optLong("id")) ? counts.get(a.optLong("id")) : 0;
            int cb = counts.containsKey(b.optLong("id")) ? counts.get(b.optLong("id")) : 0;
            return cb - ca;
        });
        for (JSONObject c : committees) {
            LinearLayout item = Ui.card(app);
            item.addView(Ui.text(app, c.optString("n"), 17, true, Ui.INK));
            item.addView(Ui.mute(app, (c.optString("t").isEmpty() ? "" : c.optString("t") + " · ")
                    + (counts.containsKey(c.optLong("id")) ? counts.get(c.optLong("id")) : 0) + " ישיבות"));
            item.setOnClickListener(v -> {
                openCommittee = c.optLong("id");
                app.refresh();
            });
            root.addView(item, Ui.match(app, 8));
        }
        return root;
    }

    private View detail(MainActivity app, LinearLayout root) {
        root.addView(Ui.button(app, "חזרה לכל הוועדות", v -> {
            openCommittee = -1;
            app.refresh();
        }));
        JSONArray committees = Cache.arr(data, "committees");
        String name = "ועדה " + openCommittee;
        for (int i = 0; i < committees.length(); i++) {
            if (committees.optJSONObject(i).optLong("id") == openCommittee) {
                name = committees.optJSONObject(i).optString("n");
            }
        }
        root.addView(Ui.text(app, name, 22, true, Ui.INK));
        root.addView(Ui.heading(app, "חברי הוועדה"));
        Set<Long> seen = new HashSet<>();
        JSONArray roles = Cache.arr(data, "roles");
        String from = data.optString("from", DEFAULT_FROM);
        String to = data.optString("to", DEFAULT_TO);
        for (int i = 0; i < roles.length(); i++) {
            JSONObject r = roles.optJSONObject(i);
            String s = r.optString("s");
            String f = r.optString("f");
            boolean inWindow = (s.isEmpty() || s.compareTo(to) <= 0) && (f.isEmpty() || f.compareTo(from) >= 0);
            if (r.optLong("c") != openCommittee || !inWindow || !seen.add(r.optLong("p"))) {
                continue;
            }
            JSONObject mk = null;
            for (JSONObject m : app.membersSorted()) {
                if (Odata.id(m.opt("apiPersonId")) == r.optLong("p")) {
                    mk = m;
                }
            }
            LinearLayout card = Ui.card(app);
            card.addView(Ui.body(app, mk == null ? "ח\"כ #" + r.optLong("p") : mk.optString("name")));
            card.addView(Ui.mute(app, r.optString("d", "חבר")));
            if (mk != null) {
                final String id = mk.optString("id");
                card.setOnClickListener(v -> app.push(new MkScreen(id)));
            }
            root.addView(card, Ui.match(app, 6));
        }
        root.addView(Ui.heading(app, "ישיבות (30 האחרונות)"));
        JSONArray sessions = Cache.arr(data, "sessions");
        List<JSONObject> mine = new ArrayList<>();
        for (int i = 0; i < sessions.length(); i++) {
            if (sessions.optJSONObject(i).optLong("c") == openCommittee) {
                mine.add(sessions.optJSONObject(i));
            }
        }
        java.util.Collections.sort(mine, (a, b) -> b.optString("d").compareTo(a.optString("d")));
        for (int i = 0; i < Math.min(30, mine.size()); i++) {
            JSONObject s = mine.get(i);
            LinearLayout card = Ui.card(app);
            card.addView(Ui.body(app, s.optString("d") + (s.optString("t").isEmpty() ? "" : " · " + s.optString("t"))));
            if (!s.optString("u").isEmpty()) {
                card.addView(MkScreen.linkRow(app, s.optString("u"), "", () -> {
                }));
            }
            root.addView(card, Ui.match(app, 6));
        }
        return root;
    }

    private void window(MainActivity app, String from, String to) {
        List<Ui.Field> f = new ArrayList<>();
        f.add(new Ui.Field("from", "מתאריך (YYYY-MM-DD)", Ui.TEXT).value(from).required());
        f.add(new Ui.Field("to", "עד תאריך (YYYY-MM-DD)", Ui.TEXT).value(to).required());
        Ui.form(app, "טווח ישיבות", null, f, "טעינה", v -> {
            if (!v.get("from").matches("\\d{4}-\\d{2}-\\d{2}") || !v.get("to").matches("\\d{4}-\\d{2}-\\d{2}") || v.get("from").compareTo(v.get("to")) > 0) {
                Ui.toast(app, "הטווח אינו תקין");
                return;
            }
            load(app, v.get("from"), v.get("to"));
        });
    }

    private void load(MainActivity app, String from, String to) {
        if (loading) {
            return;
        }
        loading = true;
        app.refresh();
        app.background(() -> {
            JSONArray committees = new JSONArray();
            for (JSONObject c : Odata.fetchAll("KNS_Committee", "KnessetNum eq 25", "Id,Name,CommitteeTypeDesc", 1000)) {
                committees.put(J.obj("id", Odata.id(c.opt("Id")), "n", c.optString("Name"), "t", c.optString("CommitteeTypeDesc", "")));
            }
            JSONArray sessions = new JSONArray();
            for (JSONObject s : Odata.fetchAll("KNS_CommitteeSession",
                    "KnessetNum eq 25 and StartDate ge " + from + "T00:00:00Z and StartDate le " + to + "T23:59:59Z",
                    "Id,CommitteeID,StartDate,TypeDesc,SessionUrl", 20000)) {
                String url = s.optString("SessionUrl", "");
                sessions.put(J.obj("i", Odata.id(s.opt("Id")), "c", Odata.id(s.opt("CommitteeID")),
                        "d", s.optString("StartDate", "").length() >= 10 ? s.optString("StartDate").substring(0, 10) : "",
                        "t", s.optString("TypeDesc", ""), "u", Scoring.validUrl(url) ? url : ""));
            }
            JSONArray roles = new JSONArray();
            for (JSONObject x : Odata.fetchAll("KNS_PersonToPosition", "KnessetNum eq 25 and CommitteeID ne null",
                    "PersonID,CommitteeID,DutyDesc,StartDate,FinishDate", 20000)) {
                roles.put(J.obj("p", Odata.id(x.opt("PersonID")), "c", Odata.id(x.opt("CommitteeID")), "d", x.optString("DutyDesc", ""),
                        "s", x.optString("StartDate", "").length() >= 10 ? x.optString("StartDate").substring(0, 10) : "",
                        "f", x.optString("FinishDate", "").length() >= 10 ? x.optString("FinishDate").substring(0, 10) : ""));
            }
            return J.obj("from", from, "to", to, "committees", committees, "sessions", sessions, "roles", roles);
        }, result -> {
            loading = false;
            data = result;
            Cache.write(app, FILE, result);
            app.refresh();
            Ui.toast(app, "נטענו " + result.optJSONArray("sessions").length() + " ישיבות");
        }, e -> {
            loading = false;
            app.refresh();
            Ui.toastLong(app, "הטעינה נכשלה: " + MainActivity.message(e));
        });
    }
}

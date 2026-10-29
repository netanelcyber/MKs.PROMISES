package il.mkpromises;

import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One member: promises with status and evidence, public background, imports, bills. */
final class MkScreen implements Screen {

    private final String id;
    private List<JSONObject> bills;

    MkScreen(String id) {
        this.id = id;
    }

    @Override
    public String title() {
        return "חבר כנסת";
    }

    @Override
    public View build(MainActivity app) {
        final JSONObject m = app.store.mk(id);
        LinearLayout root = Ui.vbox(app);
        if (m == null) {
            root.addView(Ui.body(app, "חבר הכנסת לא נמצא"));
            return root;
        }
        root.addView(Ui.text(app, m.optString("name"), 24, true, Ui.INK));
        String faction = m.optString("faction");
        root.addView(Ui.mute(app, (faction.isEmpty() ? "ללא סיעה" : faction) + " · הכנסת ה-" + m.optString("knesset")));
        Scoring.Member s = Scoring.member(m);
        root.addView(Ui.body(app, s.scored == 0 ? "עדיין אין ציון: נדרשים סטטוס, קישור למקור וראיה מקושרת"
                : "ציון " + s.value + "/255 · " + s.scored + " מתוך " + s.total + " הבטחות נמדדו"));

        LinearLayout head = Ui.row(app);
        head.addView(Ui.heading(app, "הבטחות"), new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(Ui.button(app, "+ הבטחה", v -> promiseForm(app, m, null)));
        root.addView(head);
        LinearLayout imports = Ui.row(app);
        imports.addView(Ui.button(app, "מאתר המפלגה", v -> PartyImport.start(app, m)));
        imports.addView(Ui.button(app, "כתבות מקור ראשון", v -> MakorImport.start(app, m)));
        root.addView(imports);
        LinearLayout more = Ui.row(app);
        more.addView(Ui.button(app, "טען פעילות בוועדות למודל", v -> CommitteeImport.auto(app, m)));
        more.addView(Ui.button(app, "פרוטוקול ידני", v -> CommitteeImport.start(app, m)));
        root.addView(more);
        LinearLayout bills2 = Ui.row(app);
        bills2.addView(Ui.button(app, "ועדות מול הצעות החוק שלו (כנסת 25)", v -> CommitteeImport.auto(app, m, true)));
        root.addView(bills2);

        JSONArray promises = m.optJSONArray("promises");
        if (promises.length() == 0) {
            root.addView(Ui.mute(app, "עדיין אין הבטחות."));
        }
        for (int i = 0; i < promises.length(); i++) {
            root.addView(promiseCard(app, m, promises.optJSONObject(i)), Ui.match(app, 8));
        }

        LinearLayout bg = Ui.row(app);
        bg.addView(Ui.heading(app, "פעילות ציבורית קודמת"), new LinearLayout.LayoutParams(0, -2, 1f));
        bg.addView(Ui.button(app, "+ פריט", v -> backgroundForm(app, m)));
        root.addView(bg);
        JSONArray background = m.optJSONArray("background");
        for (int i = 0; i < background.length(); i++) {
            final JSONObject b = background.optJSONObject(i);
            LinearLayout card = Ui.card(app);
            card.addView(Ui.body(app, b.optString("title")));
            card.addView(Ui.mute(app, (b.optString("period").isEmpty() ? "" : b.optString("period") + " · ") + b.optString("url")));
            if (!b.optString("note").isEmpty()) {
                card.addView(Ui.mute(app, b.optString("note")));
            }
            card.addView(linkRow(app, b.optString("url"), "מחיקה", () -> Ui.confirm(app, "למחוק את הפריט?", () -> {
                background.remove(J.indexOf(background, "id", b.optString("id")));
                app.save();
                app.refresh();
            })));
            root.addView(card, Ui.match(app, 8));
        }

        JSONArray assigned = m.optJSONArray("bills");
        if (assigned != null && assigned.length() > 0) {
            root.addView(Ui.heading(app, "הצעות חוק משויכות לח\"כ"));
            for (int i = 0; i < assigned.length(); i++) {
                final JSONObject b = assigned.optJSONObject(i);
                LinearLayout card = Ui.card(app);
                card.addView(Ui.body(app, b.optString("name")));
                if (!b.optString("subType").isEmpty()) {
                    card.addView(Ui.mute(app, b.optString("subType")));
                }
                card.addView(linkRow(app, b.optString("url"), "הסרה", () -> {
                    assigned.remove(J.indexOf(assigned, "id", b.optString("id")));
                    app.save();
                    app.refresh();
                }));
                root.addView(card, Ui.match(app, 8));
            }
        }
        if (m.has("apiPersonId")) {
            LinearLayout bl = Ui.row(app);
            bl.addView(Ui.heading(app, "הצעות חוק (מה-API)"), new LinearLayout.LayoutParams(0, -2, 1f));
            bl.addView(Ui.button(app, "טעינה", v -> loadBills(app, m)));
            root.addView(bl);
            if (bills != null) {
                if (bills.isEmpty()) {
                    root.addView(Ui.mute(app, "לא נמצאו הצעות חוק."));
                }
                for (JSONObject b : bills) {
                    LinearLayout card = Ui.card(app);
                    card.addView(Ui.body(app, b.optString("Name")));
                    card.addView(Ui.mute(app, "הכנסת ה-" + b.optInt("KnessetNum") + " · " + b.optString("SubTypeDesc")));
                    card.addView(Ui.button(app, "שיוך לחבר הכנסת", v ->
                            Assign.bill(app, m, b.optLong("BillID"), b.optString("Name"), b.optString("SubTypeDesc"))));
                    root.addView(card, Ui.match(app, 8));
                }
            }
        }

        root.addView(Ui.button(app, "מחיקת חבר הכנסת", v -> Ui.confirm(app, "למחוק את חבר הכנסת וכל הנתונים שלו?", () -> {
            JSONArray all = app.store.mks();
            all.remove(J.indexOf(all, "id", id));
            if (app.save()) {
                app.pop();
            }
        })), Ui.match(app, 24));
        return root;
    }

    private View promiseCard(MainActivity app, JSONObject m, JSONObject p) {
        LinearLayout card = Ui.card(app);
        card.addView(Ui.body(app, p.optString("text")));
        StringBuilder meta = new StringBuilder();
        if (!p.optString("topic").isEmpty()) {
            meta.append(p.optString("topic")).append(" · ");
        }
        String election = p.optString("election");
        if (Vocab.ELECTION.containsKey(election)) {
            meta.append(Vocab.ELECTION.get(election)).append(" · ");
        }
        if (!p.optString("sourceDate").isEmpty()) {
            meta.append(p.optString("sourceDate")).append(" · ");
        }
        meta.append(p.optString("sourceUrl").isEmpty() ? "ללא מקור" : p.optString("sourceUrl"));
        card.addView(Ui.mute(app, meta.toString()));

        final List<String> keys = new ArrayList<>(Vocab.STATUS.keySet());
        Spinner status = new Spinner(app);
        status.setAdapter(new ArrayAdapter<>(app, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>(Vocab.STATUS.values())));
        final int current = Math.max(0, keys.indexOf(p.optString("status", "unchecked")));
        status.setSelection(current, false);
        status.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long rowId) {
                if (pos != current) {
                    J.put(p, "status", keys.get(pos));
                    app.save();
                    app.refresh();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        card.addView(status);
        Scoring.Result r = Scoring.promise(p);
        card.addView(Ui.mute(app, r.value == null ? "לא נמדדת: " + r.reason : "נמדדת: " + r.value + "/255"));
        if (!p.optString("note").isEmpty()) {
            card.addView(Ui.mute(app, p.optString("note")));
        }

        JSONArray evidence = p.optJSONArray("evidence");
        for (int i = 0; i < evidence.length(); i++) {
            final JSONObject e = evidence.optJSONObject(i);
            LinearLayout ev = Ui.vbox(app);
            ev.setPadding(Ui.dp(app, 8), Ui.dp(app, 6), 0, Ui.dp(app, 6));
            ev.addView(Ui.body(app, e.optString("title")));
            if (!e.optString("excerpt").isEmpty()) {
                ev.addView(Ui.mute(app, "“" + e.optString("excerpt") + "”"));
            }
            ev.addView(Ui.mute(app, e.optString("type") + " · " + Vocab.EFFECT.getOrDefault(e.optString("effect"), "")
                    + (e.optString("date").isEmpty() ? "" : " · " + e.optString("date"))));
            ev.addView(linkRow(app, e.optString("url"), "מחיקה", () -> {
                evidence.remove(J.indexOf(evidence, "id", e.optString("id")));
                app.save();
                app.refresh();
            }));
            card.addView(ev);
        }

        LinearLayout actions = Ui.row(app);
        actions.addView(Ui.button(app, "+ ראיה", v -> evidenceForm(app, p)));
        actions.addView(Ui.button(app, "עריכה", v -> promiseForm(app, m, p)));
        actions.addView(Ui.button(app, "מחיקה", v -> Ui.confirm(app, "למחוק את ההבטחה?", () -> {
            JSONArray all = m.optJSONArray("promises");
            all.remove(J.indexOf(all, "id", p.optString("id")));
            app.save();
            app.refresh();
        })));
        card.addView(actions);
        return card;
    }

    /** A row with an "open link" action (when the URL is valid) and one more action. */
    static View linkRow(MainActivity app, String url, String label, Runnable action) {
        LinearLayout row = Ui.row(app);
        if (Scoring.validUrl(url)) {
            row.addView(Ui.button(app, "פתיחת מקור", v -> {
                try {
                    app.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ex) {
                    Ui.toast(app, "לא ניתן לפתוח את הקישור");
                }
            }));
        }
        row.addView(Ui.button(app, label, v -> action.run()));
        return row;
    }

    private void promiseForm(MainActivity app, JSONObject m, JSONObject existing) {
        List<Ui.Field> f = new ArrayList<>();
        JSONObject p = existing == null ? new JSONObject() : existing;
        f.add(new Ui.Field("text", "ההבטחה", Ui.MULTI).value(p.optString("text")).required());
        f.add(new Ui.Field("topic", "נושא", Ui.TEXT).value(p.optString("topic")));
        f.add(new Ui.Field("sourceUrl", "קישור למקור ההבטחה", Ui.URL).value(p.optString("sourceUrl")));
        f.add(new Ui.Field("sourceDate", "תאריך המקור (YYYY-MM-DD)", Ui.TEXT).value(p.optString("sourceDate")));
        f.add(Vocab.select(new Ui.Field("election", "בחירות", Ui.SELECT).value(p.optString("election", "2022")), Vocab.ELECTION));
        f.add(new Ui.Field("note", "הערה", Ui.MULTI).value(p.optString("note")));
        Ui.form(app, existing == null ? "הבטחה חדשה" : "עריכת הבטחה", null, f, "שמירה", v -> {
            JSONObject target = existing;
            if (target == null) {
                target = J.obj("id", Store.uid(), "status", "unchecked", "evidence", new JSONArray());
                m.optJSONArray("promises").put(target);
            }
            for (String k : new String[]{"text", "topic", "sourceUrl", "sourceDate", "election", "note"}) {
                J.put(target, k, v.get(k));
            }
            app.save();
            app.refresh();
        });
    }

    private void evidenceForm(MainActivity app, JSONObject p) {
        List<Ui.Field> f = new ArrayList<>();
        f.add(new Ui.Field("title", "תיאור", Ui.TEXT).required());
        Ui.Field type = new Ui.Field("type", "סוג", Ui.SELECT);
        for (String t : Vocab.EVIDENCE_TYPES) {
            type.option(t, t);
        }
        f.add(type);
        f.add(Vocab.select(new Ui.Field("effect", "השפעה על ההבטחה (נקבעת על ידך)", Ui.SELECT).value("context"), Vocab.EFFECT));
        f.add(new Ui.Field("date", "תאריך (YYYY-MM-DD)", Ui.TEXT));
        f.add(new Ui.Field("url", "קישור למקור", Ui.URL).required());
        Ui.form(app, "ראיה", null, f, "שמירה", v -> {
            p.optJSONArray("evidence").put(J.obj("id", Store.uid(), "title", v.get("title"), "type", v.get("type"),
                    "effect", v.get("effect"), "date", v.get("date"), "url", v.get("url")));
            app.save();
            app.refresh();
        });
    }

    private void backgroundForm(MainActivity app, JSONObject m) {
        List<Ui.Field> f = new ArrayList<>();
        f.add(new Ui.Field("title", "תיאור (תפקיד / פרסום / הצהרה)", Ui.TEXT).required());
        f.add(new Ui.Field("period", "תקופה", Ui.TEXT));
        f.add(new Ui.Field("url", "קישור למקור", Ui.URL).required());
        f.add(new Ui.Field("note", "הערה", Ui.MULTI));
        Ui.form(app, "פעילות ציבורית קודמת", null, f, "שמירה", v -> {
            m.optJSONArray("background").put(J.obj("id", Store.uid(), "title", v.get("title"), "period", v.get("period"),
                    "url", v.get("url"), "note", v.get("note")));
            app.save();
            app.refresh();
        });
    }

    private void loadBills(MainActivity app, JSONObject m) {
        final long personId = Odata.id(m.opt("apiPersonId"));
        if (personId < 0) {
            Ui.toast(app, "לחבר הכנסת אין מזהה API תקין");
            return;
        }
        Ui.toast(app, "טוען הצעות חוק…");
        app.background(() -> Members.bills(personId), list -> {
            bills = list;
            app.refresh();
            Ui.toast(app, "נטענו " + list.size() + " הצעות");
        });
    }
}

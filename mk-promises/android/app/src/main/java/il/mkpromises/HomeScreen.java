package il.mkpromises;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The list of members with search, and the entry points to loading data. */
final class HomeScreen implements Screen {

    private String query = "";

    @Override
    public String title() {
        return "מעקב הבטחות";
    }

    @Override
    public View build(MainActivity app) {
        LinearLayout root = Ui.vbox(app);

        LinearLayout actions = Ui.row(app);
        actions.addView(Ui.button(app, "+ חבר כנסת", v -> addMember(app)));
        actions.addView(Ui.button(app, "טעינה מהכנסת", v -> loadFromKnesset(app)));
        root.addView(actions);
        LinearLayout more = Ui.row(app);
        more.addView(Ui.button(app, "הצעות חוק", v -> app.push(new BillsScreen())));
        more.addView(Ui.button(app, "ועדות", v -> app.push(new CommitteesScreen())));
        more.addView(Ui.button(app, "מודל", v -> ModelScreen.open(app)));
        root.addView(more);

        EditText search = new EditText(app);
        search.setHint("חיפוש לפי שם או סיעה");
        search.setSingleLine(true);
        search.setText(query);
        root.addView(search, Ui.match(app, 8));

        final LinearLayout list = Ui.vbox(app);
        root.addView(list, Ui.match(app, 4));
        fill(app, list);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                fill(app, list);
            }
        });
        return root;
    }

    private void fill(MainActivity app, LinearLayout list) {
        list.removeAllViews();
        List<JSONObject> shown = new ArrayList<>();
        for (JSONObject m : app.membersSorted()) {
            if (query.isEmpty() || m.optString("name").contains(query) || m.optString("faction").contains(query)) {
                shown.add(m);
            }
        }
        if (shown.isEmpty()) {
            list.addView(Ui.mute(app, app.store.mks().length() == 0
                    ? "עדיין אין חברי כנסת. אפשר להוסיף ידנית או לטעון מהכנסת (מחיבור ישראלי)."
                    : "אין התאמות לחיפוש."));
            return;
        }
        for (JSONObject m : shown) {
            LinearLayout card = Ui.card(app);
            card.addView(Ui.text(app, m.optString("name"), 17, true, Ui.INK));
            String faction = m.optString("faction");
            card.addView(Ui.mute(app, (faction.isEmpty() ? "ללא סיעה" : faction) + " · הכנסת ה-" + m.optString("knesset")
                    + (m.optBoolean("isNew") ? " · חדש" : "")));
            Scoring.Member s = Scoring.member(m);
            card.addView(Ui.mute(app, s.scored == 0
                    ? s.total + " הבטחות · עדיין אין ציון (נדרשים סטטוס, מקור וראיה)"
                    : "ציון " + s.value + "/255 · " + s.scored + " מתוך " + s.total + " הבטחות נמדדו"));
            card.setOnClickListener(v -> app.push(new MkScreen(m.optString("id"))));
            list.addView(card, Ui.match(app, 8));
        }
    }

    private void addMember(MainActivity app) {
        List<Ui.Field> f = new ArrayList<>();
        f.add(new Ui.Field("name", "שם", Ui.TEXT).required());
        f.add(new Ui.Field("faction", "סיעה", Ui.TEXT));
        f.add(new Ui.Field("knesset", "מספר הכנסת", Ui.NUMBER).value("25"));
        f.add(new Ui.Field("isNew", "חבר כנסת חדש", Ui.CHECK));
        Ui.form(app, "חבר כנסת", null, f, "הוספה", v -> {
            JSONObject m = J.obj("id", Store.uid(), "name", v.get("name"), "faction", v.get("faction"),
                    "knesset", v.get("knesset"), "isNew", "true".equals(v.get("isNew")),
                    "promises", new JSONArray(), "background", new JSONArray());
            app.store.mks().put(m);
            if (app.save()) {
                app.push(new MkScreen(m.optString("id")));
            }
        });
    }

    private void loadFromKnesset(MainActivity app) {
        List<Ui.Field> f = new ArrayList<>();
        f.add(new Ui.Field("k", "מספר הכנסת", Ui.NUMBER).value("25").required());
        Ui.form(app, "טעינת חברי כנסת", "הטעינה פועלת רק מחיבור ישראלי. חברים קיימים לא נדרסים.", f, "טעינה", (Map<String, String> v) -> {
            final int k;
            try {
                k = Integer.parseInt(v.get("k"));
            } catch (NumberFormatException e) {
                Ui.toast(app, "מספר הכנסת אינו תקין");
                return;
            }
            Ui.toast(app, "טוען חברי כנסת…");
            app.background(() -> Members.load(k), people -> {
                if (people.isEmpty()) {
                    Ui.toast(app, "לא נמצאו חברי כנסת בכנסת ה-" + k);
                    return;
                }
                int[] r = Members.merge(app.store, people, k);
                if (app.save()) {
                    app.refresh();
                    Ui.toast(app, "נוספו " + r[0] + " חברי כנסת" + (r[1] > 0 ? " · הושלמו סיעות ל-" + r[1] : ""));
                }
            });
        });
    }
}

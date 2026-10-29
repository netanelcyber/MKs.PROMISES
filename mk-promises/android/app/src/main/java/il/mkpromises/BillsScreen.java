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

/** All private bills of one Knesset, loaded page by page, searchable, assignable to a member's promise. */
final class BillsScreen implements Screen {

    private int knesset = 25;
    private String query = "";
    private boolean loading;
    private String progress = "";
    private JSONArray rows;   // [{i,n,s}]

    @Override
    public String title() {
        return "הצעות חוק";
    }

    @Override
    public View build(MainActivity app) {
        if (rows == null) {
            JSONObject cached = Cache.read(app, "bills-" + knesset + ".json");
            rows = Cache.arr(cached, "rows");
        }
        LinearLayout root = Ui.vbox(app);
        root.addView(Ui.mute(app, "הצעות החוק נטענות מהכנסת (מחיבור ישראלי) ונשמרות במכשיר בנפרד מהגיבוי."));
        LinearLayout actions = Ui.row(app);
        actions.addView(Ui.button(app, rows.length() == 0 ? "טעינה מהכנסת" : "רענון מהכנסת", v -> askKnesset(app)));
        root.addView(actions);
        root.addView(Ui.mute(app, loading ? progress : rows.length() + " הצעות חוק בכנסת ה-" + knesset));

        EditText search = new EditText(app);
        search.setHint("חיפוש בשם ההצעה");
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
        int shown = 0;
        for (int i = 0; i < rows.length() && shown < 60; i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null || (!query.isEmpty() && !r.optString("n").contains(query))) {
                continue;
            }
            shown++;
            LinearLayout card = Ui.card(app);
            card.addView(Ui.body(app, r.optString("n")));
            card.addView(Ui.mute(app, r.optString("s")));
            card.addView(Ui.button(app, "שיוך לחבר כנסת", v -> assign(app, r)));
            list.addView(card, Ui.match(app, 8));
        }
        if (shown == 60) {
            list.addView(Ui.mute(app, "מוצגות 60 ההתאמות הראשונות. צמצמו את החיפוש."));
        }
    }

    interface Count {
        void on(int n);
    }

    /** Every private bill of one Knesset, 100 per request. */
    static JSONArray fetchAll(int k, Count progress) throws Exception {
        JSONArray out = new JSONArray();
        for (int skip = 0; skip < 300000; skip += 100) {
            String url = Odata.url("KNS_Bill", "KnessetNum eq " + k, "Id,KnessetNum,Name,SubTypeDesc") + "&$skip=" + skip + "&$top=100";
            JSONArray page = Odata.page(url);
            for (int i = 0; i < page.length(); i++) {
                JSONObject b = page.optJSONObject(i);
                if (b != null && Odata.id(b.opt("Id")) > 0) {
                    out.put(J.obj("i", Odata.id(b.opt("Id")), "n", b.optString("Name"), "s", b.optString("SubTypeDesc")));
                }
            }
            progress.on(out.length());
            if (page.length() < 100) {
                break;
            }
        }
        return out;
    }

    private void askKnesset(MainActivity app) {
        if (loading) {
            Ui.toast(app, "הטעינה כבר מתבצעת");
            return;
        }
        List<Ui.Field> f = new ArrayList<>();
        f.add(new Ui.Field("k", "מספר הכנסת", Ui.NUMBER).value(String.valueOf(knesset)).required());
        Ui.form(app, "טעינת הצעות חוק", "הטעינה עוברת על כל ההצעות 100 בכל פעם ויכולה לקחת כמה דקות.", f, "טעינה", (Map<String, String> v) -> {
            try {
                knesset = Integer.parseInt(v.get("k"));
            } catch (NumberFormatException e) {
                Ui.toast(app, "מספר הכנסת אינו תקין");
                return;
            }
            final int k = knesset;
            loading = true;
            progress = "טוען…";
            app.refresh();
            app.background(() -> {
                JSONArray out = fetchAll(k, count -> app.runOnUiThread(() -> {
                    progress = "נטענו " + count + " הצעות…";
                }));
                return out;
            }, out -> {
                loading = false;
                rows = out;
                Cache.write(app, "bills-" + k + ".json", J.obj("knesset", k, "rows", out));
                app.refresh();
                Ui.toast(app, "נטענו " + out.length() + " הצעות");
            }, e -> {
                loading = false;
                app.refresh();
                Ui.toastLong(app, "הטעינה נכשלה: " + MainActivity.message(e));
            });
        });
    }

    private void assign(MainActivity app, JSONObject bill) {
        List<JSONObject> members = app.membersSorted();
        if (members.isEmpty()) {
            Ui.toast(app, "קודם הוסיפו או טענו חברי כנסת");
            return;
        }
        List<Ui.Field> f = new ArrayList<>();
        Ui.Field who = new Ui.Field("m", "חבר כנסת", Ui.SELECT);
        for (JSONObject m : members) {
            who.option(m.optString("id"), m.optString("name"));
        }
        f.add(who);
        Ui.form(app, "שיוך הצעת חוק לחבר כנסת", bill.optString("n"), f, "שיוך", v -> {
            JSONObject m = app.store.mk(v.get("m"));
            if (m != null) {
                Assign.bill(app, m, bill.optLong("i"), bill.optString("n"), bill.optString("s"));
            }
        });
    }
}

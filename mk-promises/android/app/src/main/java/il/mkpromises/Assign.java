package il.mkpromises;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Attach a linked item (bill, committee, article...) to one of a member's promises as evidence. Never changes the status. */
final class Assign {

    private Assign() {
    }

    interface Done {
        void done(int added);
    }

    static void evidence(MainActivity app, JSONObject member, String title, String type, String url, Runnable after) {
        multiple(app, member, new String[]{title}, new String[]{url}, type, null, n -> {
            if (after != null) {
                after.run();
            }
        });
    }

    /** titles[i] and urls[i] describe one item. hint is shown above the form. Calls done with how many were added. */
    static void multiple(MainActivity app, JSONObject member, String[] titles, String[] urls, String type, String hint, Done done) {
        JSONArray promises = member.optJSONArray("promises");
        if (promises.length() == 0) {
            Ui.toast(app, "קודם הוסיפו הבטחה לחבר הכנסת");
            return;
        }
        List<Ui.Field> f = new ArrayList<>();
        Ui.Field target = new Ui.Field("p", "הבטחה", Ui.SELECT);
        for (int i = 0; i < promises.length(); i++) {
            String text = promises.optJSONObject(i).optString("text");
            target.option(promises.optJSONObject(i).optString("id"), text.substring(0, Math.min(60, text.length())));
        }
        f.add(target);
        f.add(Vocab.select(new Ui.Field("effect", "השפעה לפי בדיקת המקור", Ui.SELECT).value("context"), Vocab.EFFECT));
        Ui.form(app, "שיוך להבטחה", hint == null ? "השיוך מוסיף ראיה מקושרת בלבד. הוא אינו משנה סטטוס, וההשפעה נקבעת על ידך." : hint,
                f, "שיוך", v -> {
                    JSONObject p = J.find(promises, "id", v.get("p"));
                    if (p == null) {
                        return;
                    }
                    JSONArray evidence = J.array(p, "evidence");
                    int added = 0;
                    for (int i = 0; i < titles.length; i++) {
                        boolean exists = false;
                        for (int k = 0; k < evidence.length(); k++) {
                            exists |= urls[i].equals(evidence.optJSONObject(k).optString("url"));
                        }
                        if (exists) {
                            continue;
                        }
                        evidence.put(J.obj("id", Store.uid(), "title", titles[i], "type", type, "effect", v.get("effect"),
                                "date", "", "url", urls[i]));
                        added++;
                    }
                    app.save();
                    app.refresh();
                    Ui.toast(app, added > 0 ? "נוספו " + added : "כבר קיים");
                    done.done(added);
                });
    }
}

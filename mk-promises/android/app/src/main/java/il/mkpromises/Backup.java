package il.mkpromises;

import android.content.Intent;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Backup, restore and wipe, using the same JSON format as the web app. */
final class Backup {

    private Backup() {
    }

    static void menu(MainActivity app) {
        List<Ui.Field> f = new ArrayList<>();
        f.add(new Ui.Field("act", "פעולה", Ui.SELECT).option("export", "ייצוא / שיתוף הנתונים")
                .option("labels", "ייצוא תיוגים לאימון המודל (הכנסת ה-25)")
                .option("import", "ייבוא מגיבוי JSON שהודבק").option("wipe", "מחיקת כל הנתונים"));
        Ui.form(app, "גיבוי ושחזור", null, f, "המשך", v -> {
            switch (v.get("act")) {
                case "export":
                    share(app, app.store.state.toString());
                    break;
                case "labels":
                    Labels.export(app);
                    break;
                case "import":
                    importDialog(app);
                    break;
                default:
                    Ui.confirm(app, "למחוק הכול? אי אפשר לשחזר.", () -> {
                        if (app.store.write(Store.empty())) {
                            app.store.state = Store.empty();
                            app.refresh();
                        }
                    });
            }
        });
    }

    static void share(MainActivity app, String text) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text);
        app.startActivity(Intent.createChooser(send, "שיתוף"));
    }

    private static void importDialog(MainActivity app) {
        List<Ui.Field> f = new ArrayList<>();
        f.add(new Ui.Field("json", "תוכן הגיבוי", Ui.MULTI).required());
        Ui.form(app, "הדביקו JSON", null, f, "ייבוא", (Map<String, String> v) -> {
            JSONObject parsed;
            try {
                parsed = Store.validate(new JSONObject(v.get("json")));
            } catch (Exception e) {
                Ui.toast(app, "ה-JSON אינו תקין: " + MainActivity.message(e));
                return;
            }
            final JSONObject next = parsed;
            Ui.confirm(app, "להחליף את הנתונים הנוכחיים?", () -> {
                if (app.store.write(next)) {
                    app.store.state = next;
                    app.refresh();
                } else {
                    Ui.toast(app, "השמירה נכשלה");
                }
            });
        });
    }
}

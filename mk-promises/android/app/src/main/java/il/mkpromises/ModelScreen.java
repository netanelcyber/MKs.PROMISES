package il.mkpromises;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The stance model: status, training on the device from the user's labels, loading a file, removing it. */
final class ModelScreen {

    private ModelScreen() {
    }

    static void open(MainActivity app) {
        Stance.Model m = Stance.get(app);
        String status;
        if (m == null) {
            status = "אין מודל. אפשר לאמן מהתיוגים שלך או לטעון קובץ שיוצא מ-ml/export_js.py.";
        } else {
            JSONObject mt = m.metrics;
            status = (m.light ? "מודל קל שאומן במכשיר" : "מודל BiLSTM+DNN, " + m.lstmLayers + " שכבות (מובנה ב-APK או נטען מקובץ)") + (m.synthetic ? " (אומן על נתונים סינתטיים, לא אמין)" : "")
                    + (mt == null ? "" : "\nדיוק בולידציה: " + Math.round(mt.optDouble("accuracy") * 100) + "%, macro-F1: "
                    + Math.round(mt.optDouble("macroF1") * 100) + "% על " + mt.optInt("valSize") + " דוגמאות");
        }
        List<Ui.Field> f = new ArrayList<>();
        f.add(new Ui.Field("act", "פעולה", Ui.SELECT).option("auto", "אימון אוטומטי על כל הצעות החוק (תיוג חלש)").option("train", "אימון מודל על המכשיר מהתיוגים שלי")
                .option("b2", "מודל מובנה: 2 שכבות LSTM").option("b4", "מודל מובנה: 4 שכבות LSTM")
                .option("b8", "מודל מובנה: 8 שכבות LSTM").option("load", "טעינת קובץ מודל").option("clear", "הסרת המודל שלי (חוזר למובנה)"));
        Ui.form(app, "מודל הצעות", status + "\nההצעות אינן משנות סטטוס ואינן נבחרות אוטומטית.", f, "המשך", v -> {
            switch (v.get("act")) {
                case "auto":
                    AutoTrain.run(app);
                    break;
                case "train":
                    train(app);
                    break;
                case "b2":
                case "b4":
                case "b8":
                    int layers = Integer.parseInt(v.get("act").substring(1));
                    try {
                        Stance.useBundled(app, layers);
                        Ui.toast(app, "הופעל מודל " + layers + " שכבות (אומן על נתונים סינתטיים, לא אמין)");
                    } catch (Exception e) {
                        Ui.toastLong(app, "הטעינה נכשלה: " + MainActivity.message(e));
                    }
                    break;
                case "load":
                    app.pickFile(text -> {
                        try {
                            Stance.Model loaded = Stance.fromJson(text);
                            Stance.set(app, loaded);
                            Ui.toast(app, "המודל נטען" + (loaded.synthetic ? " (סינתטי בלבד)" : ""));
                        } catch (Exception e) {
                            Ui.toastLong(app, "טעינת המודל נכשלה: " + MainActivity.message(e));
                        }
                    });
                    break;
                default:
                    Stance.clear(app);
                    Ui.toast(app, "המודל הוסר");
            }
        });
    }

    private static void train(MainActivity app) {
        List<Stance.Row> rows = Labels.rows(app.store.mks());
        int[] seen = new int[3];
        for (Stance.Row r : rows) {
            seen[r.label]++;
        }
        if (rows.size() < 30 || seen[0] == 0 || seen[1] == 0 || seen[2] == 0) {
            Ui.toastLong(app, "נדרשות לפחות 30 ראיות מתויגות מכל שלוש ההשפעות (כרגע " + rows.size() + ")");
            return;
        }
        Ui.toast(app, "מאמן… (" + rows.size() + " דוגמאות)");
        app.background(() -> Stance.train(rows, 25, (epoch, loss, acc) ->
                app.runOnUiThread(() -> Ui.toast(app, "סבב " + epoch + " · דיוק " + Math.round(acc * 100) + "%")), 1L), r -> {
            Ui.confirm(app, "האימון הסתיים. ולידציה על " + r.valSize + " דוגמאות שלא נראו באימון: דיוק " + Math.round(r.accuracy * 100)
                    + "%, macro-F1 " + Math.round(r.macroF1 * 100) + "%. דיוק נמוך או מעט תיוגים אומרים שההצעות לא אמינות. לשמור את המודל?", () -> {
                try {
                    Stance.set(app, r.model);
                    Ui.toast(app, "המודל נשמר ופעיל");
                } catch (Exception e) {
                    Ui.toastLong(app, "השמירה נכשלה: " + MainActivity.message(e));
                }
            });
        });
    }
}

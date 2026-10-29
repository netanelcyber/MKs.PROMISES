package il.mkpromises;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Read a party platform page chosen by the user and offer lines that look like promises. */
final class PartyImport {

    private PartyImport() {
    }

    static void start(MainActivity app, JSONObject member) {
        List<Ui.Field> f = new ArrayList<>();
        f.add(new Ui.Field("url", "קישור לעמוד מצע / הבטחות באתר המפלגה", Ui.URL).required());
        f.add(Vocab.select(new Ui.Field("election", "בחירות", Ui.SELECT).value("2022"), Vocab.ELECTION));
        Ui.form(app, "הבטחות מאתר המפלגה: " + member.optString("name"),
                "האפליקציה קוראת את העמוד ומציעה שורות שנראות כהבטחות. אתה בוחר מה לייבא. הקישור נשמר כמקור ההבטחה.", f, "חיפוש", v -> {
                    final String url = v.get("url");
                    if (!url.toLowerCase(Locale.ROOT).startsWith("https://")) {
                        Ui.toast(app, "נדרש קישור https");
                        return;
                    }
                    Ui.toast(app, "קורא את עמוד המפלגה…");
                    app.background(() -> Html.promises(Http.get(url, "text/html", Http.PUBLIC)), lines -> {
                        if (lines.isEmpty()) {
                            Ui.toast(app, "לא נמצאו שורות שנראות כהבטחות בעמוד");
                            return;
                        }
                        choose(app, member, url, v.get("election"), lines);
                    });
                });
    }

    private static void choose(MainActivity app, JSONObject member, String url, String election, List<String> lines) {
        List<Ui.Field> f = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            f.add(new Ui.Field("a" + i, lines.get(i), Ui.CHECK));
        }
        Ui.form(app, "בחרו הבטחות לייבוא", "בדקו מול העמוד עצמו. כותרות ותפריטים עלולים להיכנס בטעות.", f, "ייבוא", v -> {
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            JSONArray promises = member.optJSONArray("promises");
            int added = 0;
            for (int i = 0; i < lines.size(); i++) {
                if (!"true".equals(v.get("a" + i))) {
                    continue;
                }
                boolean exists = false;
                for (int k = 0; k < promises.length(); k++) {
                    JSONObject p = promises.optJSONObject(k);
                    exists |= lines.get(i).equals(p.optString("text")) && url.equals(p.optString("sourceUrl"));
                }
                if (exists) {
                    continue;
                }
                promises.put(J.obj("id", Store.uid(), "text", lines.get(i), "topic", "", "sourceUrl", url, "sourceDate", today,
                        "status", "unchecked", "note", "", "evidence", new JSONArray(), "election", election));
                added++;
            }
            app.save();
            app.refresh();
            Ui.toast(app, "נוספו " + added + " הבטחות");
        });
    }
}

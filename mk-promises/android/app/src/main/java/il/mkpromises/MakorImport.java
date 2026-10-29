package il.mkpromises;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Search Makor Rishon for a member's name and attach chosen articles (title and link only, never the text). */
final class MakorImport {

    private static final String SITE = "https://www.makorrishon.co.il/";

    private MakorImport() {
    }

    static void start(MainActivity app, JSONObject member) {
        final String name = member.optString("name");
        Ui.toast(app, "מחפש ב\"מקור ראשון\"…");
        app.background(() -> {
            String q = URLEncoder.encode(name, "UTF-8");
            return Html.articles(Http.get(SITE + "?s=" + q, "text/html", Http.MAKOR_RISHON), SITE);
        }, found -> {
            if (found.isEmpty()) {
                Ui.toast(app, "לא נמצאו כתבות עבור " + name);
                return;
            }
            choose(app, member, new ArrayList<>(found.entrySet()));
        });
    }

    private static void choose(MainActivity app, JSONObject member, List<Map.Entry<String, String>> items) {
        JSONArray promises = member.optJSONArray("promises");
        List<Ui.Field> f = new ArrayList<>();
        Ui.Field target = new Ui.Field("t", "לאן לשייך", Ui.SELECT);
        for (int i = 0; i < promises.length(); i++) {
            String text = promises.optJSONObject(i).optString("text");
            target.option(promises.optJSONObject(i).optString("id"), "ראיה להבטחה: " + text.substring(0, Math.min(50, text.length())));
        }
        target.option("bg", "פעילות ציבורית קודמת");
        f.add(target);
        f.add(Vocab.select(new Ui.Field("effect", "השפעה (לראיה להבטחה; נקבעת על ידך)", Ui.SELECT).value("context"), Vocab.EFFECT));
        for (int i = 0; i < items.size(); i++) {
            String title = items.get(i).getValue();
            String hint = Stance.hint(app, member, title);
            f.add(new Ui.Field("a" + i, title + (hint == null ? "" : "  [" + hint + "]"), Ui.CHECK));
        }
        Ui.form(app, "כתבות ב\"מקור ראשון\": " + member.optString("name"),
                "נשמרים כותרת וקישור בלבד. תוצאות החיפוש לא מאומתות: סמנו רק כתבות שבאמת עוסקות בחבר הכנסת.", f, "ייבוא", v -> {
                    int added = 0;
                    for (int i = 0; i < items.size(); i++) {
                        if (!"true".equals(v.get("a" + i))) {
                            continue;
                        }
                        String url = items.get(i).getKey();
                        String title = items.get(i).getValue();
                        if ("bg".equals(v.get("t"))) {
                            JSONArray bg = J.array(member, "background");
                            if (J.find(bg, "url", url) == null) {
                                bg.put(J.obj("id", Store.uid(), "title", title, "period", "", "url", url, "note", "מקור ראשון"));
                                added++;
                            }
                        } else {
                            JSONObject p = J.find(promises, "id", v.get("t"));
                            JSONArray ev = p == null ? null : J.array(p, "evidence");
                            if (ev != null && J.find(ev, "url", url) == null) {
                                ev.put(J.obj("id", Store.uid(), "title", title, "type", "כתבה", "effect", v.get("effect"), "date", "", "url", url));
                                added++;
                            }
                        }
                    }
                    app.save();
                    app.refresh();
                    Ui.toast(app, added > 0 ? "נוספו " + added + " כתבות" : "לא נבחרו כתבות חדשות");
                });
    }
}

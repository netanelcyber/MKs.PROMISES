package il.mkpromises;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Attaches bills of one Knesset to a member automatically, in one of two cases:
 * he is an initiator of the bill, or he voted in ALL THREE readings (first, second, third) of it.
 * The vote-to-bill link is read from KNS_PlenumVote.ItemID and the reading from the vote's text fields.
 * NOT VERIFIED against the live server; when no vote can be tied to a bill and a reading, the result says so.
 */
final class AutoAssign {

    private static final int KNESSET = 25;
    private static final String SINCE = "2022-11-15T00:00:00Z";
    private static final int MAX_VOTES = 4000;
    private static final Pattern READING = Pattern.compile("קריאה\\s+(ראשונה|שנייה|שניה|שלישית)");

    private AutoAssign() {
    }

    static void run(MainActivity app, JSONObject member) {
        if (!member.has("apiPersonId")) {
            Ui.toast(app, "השיוך האוטומטי דורש חבר כנסת שנטען מהכנסת");
            return;
        }
        final long pid = member.optLong("apiPersonId");
        Ui.toast(app, "משייך הצעות חוק… זה יכול לקחת כמה דקות");
        app.background(() -> {
            JSONArray bills = Cache.arr(Cache.read(app, "bills-" + KNESSET + ".json"), "rows");
            if (bills.length() == 0) {
                bills = BillsScreen.fetchAll(KNESSET, n -> {
                });
                Cache.write(app, "bills-" + KNESSET + ".json", J.obj("knesset", KNESSET, "rows", bills));
            }
            Map<Long, JSONObject> byId = new HashMap<>();
            for (int i = 0; i < bills.length(); i++) {
                byId.put(bills.optJSONObject(i).optLong("i"), bills.optJSONObject(i));
            }
            Map<Long, String> found = new HashMap<>();   // billId -> role
            for (JSONObject b : Members.bills(pid)) {
                if (b.optInt("KnessetNum") == KNESSET) {
                    found.put(b.optLong("BillID"), "יוזם");
                }
            }
            int initiator = found.size();

            Set<Long> voteIds = new HashSet<>();
            for (JSONObject r : Odata.fetchAll("KNS_PlenumVoteResult", "MkId eq " + pid + " and VoteDate ge " + SINCE,
                    "VoteID,ResultDesc", MAX_VOTES)) {
                String res = r.optString("ResultDesc");
                if (res.contains("בעד") || res.contains("נגד") || res.contains("נמנע")) {
                    voteIds.add(Odata.id(r.opt("VoteID")));
                }
            }
            Map<Long, Set<String>> readings = new HashMap<>();
            int tied = 0;
            List<Long> ids = new ArrayList<>(voteIds);
            for (int i = 0; i < ids.size(); i += 10) {
                for (JSONObject v : Odata.fetchAll("KNS_PlenumVote", Odata.idFilter("Id", ids.subList(i, Math.min(i + 10, ids.size()))), null, 100)) {
                    long item = Odata.id(v.opt("ItemID"));
                    if (!byId.containsKey(item)) {
                        continue;
                    }
                    StringBuilder text = new StringBuilder();
                    for (Iterator<String> it = v.keys(); it.hasNext(); ) {
                        Object o = v.opt(it.next());
                        if (o instanceof String) {
                            text.append(o).append(' ');
                        }
                    }
                    Matcher m = READING.matcher(text);
                    if (m.find()) {
                        String r = m.group(1).replace("שניה", "שנייה");
                        readings.computeIfAbsent(item, x -> new HashSet<>()).add(r);
                        tied++;
                    }
                }
            }
            int voted = 0;
            for (Map.Entry<Long, Set<String>> e : readings.entrySet()) {
                if (e.getValue().size() == 3 && !found.containsKey(e.getKey())) {
                    found.put(e.getKey(), "הצביע בכל 3 הקריאות");
                    voted++;
                }
            }
            return new Object[]{found, byId, initiator, voted, voteIds.size(), tied};
        }, out -> {
            @SuppressWarnings("unchecked") Map<Long, String> found = (Map<Long, String>) out[0];
            @SuppressWarnings("unchecked") Map<Long, JSONObject> byId = (Map<Long, JSONObject>) out[1];
            int added = 0;
            for (Map.Entry<Long, String> e : found.entrySet()) {
                JSONObject b = byId.get(e.getKey());
                if (b != null && Assign.addBill(member, e.getKey(), b.optString("n"), b.optString("s"), e.getValue())) {
                    added++;
                }
            }
            app.save();
            app.refresh();
            String note = (int) out[4] > 0 && (int) out[5] == 0
                    ? " לא הצלחתי לקשור הצבעות להצעות וקריאות – ייתכן ששמות השדות בשרת שונים."
                    : "";
            Ui.toastLong(app, "שויכו " + added + " הצעות: " + out[2] + " כיוזם, " + out[3] + " עם הצבעה בכל 3 הקריאות (מתוך "
                    + out[4] + " הצבעות)." + note);
        });
    }
}

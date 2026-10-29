package il.mkpromises;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads Knesset members and a member's bills. Blocking: call from a background thread. */
final class Members {

    static final class Person {
        final long id;
        final String name;
        final String faction;

        Person(long id, String name, String faction) {
            this.id = id;
            this.name = name;
            this.faction = faction;
        }
    }

    private Members() {
    }

    /** Members (positions 43 and 61) of one Knesset with their faction (position 54). */
    static List<Person> load(int knesset) throws IOException {
        List<JSONObject> pos = Odata.fetchAll("KNS_PersonToPosition",
                "KnessetNum eq " + knesset + " and (PositionID eq 43 or PositionID eq 61 or PositionID eq 54)",
                "PersonID,PositionID,FactionName,IsCurrent,StartDate", 3000);
        Set<Long> members = new LinkedHashSet<>();
        Map<Long, JSONObject> factions = new HashMap<>();
        for (JSONObject p : pos) {
            long id = Odata.id(p.opt("PersonID"));
            long position = Odata.id(p.opt("PositionID"));
            if (id < 0 || position < 0) {
                throw new IOException("מזהה לא תקין ברשימת תפקידי הכנסת");
            }
            if (position == 43 || position == 61) {
                members.add(id);
            } else if (position == 54) {
                factions.put(id, preferred(factions.get(id), p));
            }
        }
        List<Long> ids = new ArrayList<>(members);
        List<Person> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += 10) {
            List<Long> chunk = ids.subList(i, Math.min(i + 10, ids.size()));
            for (JSONObject p : Odata.fetchAll("KNS_Person", Odata.idFilter("Id", chunk), "Id,FirstName,LastName", 100)) {
                long id = Odata.id(p.opt("Id"));
                String name = (p.optString("FirstName", "") + " " + p.optString("LastName", "")).trim();
                if (id < 0 || !members.contains(id) || name.isEmpty()) {
                    throw new IOException("נתון לא תקין ברשימת חברי הכנסת");
                }
                JSONObject f = factions.get(id);
                out.add(new Person(id, name, f == null ? "" : f.optString("FactionName", "")));
            }
        }
        return out;
    }

    private static JSONObject preferred(JSONObject a, JSONObject b) {
        if (a == null) {
            return b;
        }
        boolean ca = a.optBoolean("IsCurrent", false);
        boolean cb = b.optBoolean("IsCurrent", false);
        if (ca != cb) {
            return cb ? b : a;
        }
        return b.optString("StartDate", "").compareTo(a.optString("StartDate", "")) > 0 ? b : a;
    }

    /** Adds new members to the store; fills a missing faction on existing ones. Returns {added, updated}. */
    static int[] merge(Store store, List<Person> people, int knesset) {
        JSONArray mks = store.mks();
        int added = 0;
        int updated = 0;
        Set<Long> seen = new LinkedHashSet<>();
        for (Person p : people) {
            if (!seen.add(p.id)) {
                continue;
            }
            JSONObject existing = null;
            for (int i = 0; i < mks.length(); i++) {
                JSONObject m = mks.optJSONObject(i);
                if (m != null && Odata.id(m.opt("apiPersonId")) == p.id) {
                    existing = m;
                    break;
                }
            }
            if (existing != null) {
                if (existing.optString("faction", "").isEmpty() && !p.faction.isEmpty()) {
                    J.put(existing, "faction", p.faction);
                    updated++;
                }
                continue;
            }
            mks.put(J.obj("id", Store.uid(), "name", p.name, "faction", p.faction, "knesset", String.valueOf(knesset),
                    "isNew", false, "apiPersonId", p.id, "promises", new JSONArray(), "background", new JSONArray()));
            added++;
        }
        return new int[]{added, updated};
    }

    /** Private bills where the member is an initiator: BillID, Name, KnessetNum, SubTypeDesc. */
    static List<JSONObject> bills(long personId) throws IOException {
        List<Long> ids = new ArrayList<>();
        for (JSONObject x : Odata.fetchAll("KNS_BillInitiator", "PersonID eq " + personId, "BillID,IsInitiator", 300)) {
            if (x.has("IsInitiator") && !x.optBoolean("IsInitiator", true)) {
                continue;
            }
            long id = Odata.id(x.opt("BillID"));
            if (id < 0) {
                throw new IOException("מזהה לא תקין ברשימת הצעות החוק");
            }
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += 10) {
            for (JSONObject b : Odata.fetchAll("KNS_Bill", Odata.idFilter("Id", ids.subList(i, Math.min(i + 10, ids.size()))),
                    "Id,KnessetNum,Name,SubTypeDesc", 100)) {
                if (b.optString("Name", "").trim().isEmpty()) {
                    continue;
                }
                out.add(J.obj("BillID", Odata.id(b.opt("Id")), "KnessetNum", b.optInt("KnessetNum"), "Name", b.optString("Name"),
                        "SubTypeDesc", b.optString("SubTypeDesc", "")));
            }
        }
        return out;
    }
}

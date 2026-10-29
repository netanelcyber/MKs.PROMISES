package il.mkpromises;

import org.json.JSONArray;
import org.json.JSONObject;

/** Same method as web/promise-scoring.js (reviewed-v1): a promise counts only with a status, a source link and linked evidence. */
final class Scoring {

    static final class Result {
        final Integer value;   // 0..255 or null when excluded
        final String reason;

        Result(Integer value, String reason) {
            this.value = value;
            this.reason = reason;
        }
    }

    static final class Member {
        final Integer value;
        final int scored;
        final int total;

        Member(Integer value, int scored, int total) {
            this.value = value;
            this.scored = scored;
            this.total = total;
        }
    }

    private Scoring() {
    }

    static boolean validUrl(String s) {
        return s != null && s.matches("(?i)https?://[^\\s/]+\\S*");
    }

    static Result promise(JSONObject p) {
        String status = p.optString("status", "");
        int value;
        switch (status) {
            case "kept": value = 255; break;
            case "partial": value = 128; break;
            case "broken": value = 0; break;
            default: return new Result(null, "ההבטחה טרם סווגה לקיום, קיום חלקי או אי־קיום");
        }
        if (!validUrl(p.optString("sourceUrl", ""))) {
            return new Result(null, "נדרש קישור למקור ההבטחה");
        }
        boolean support = false;
        boolean contradict = false;
        JSONArray evidence = p.optJSONArray("evidence");
        for (int i = 0; evidence != null && i < evidence.length(); i++) {
            JSONObject e = evidence.optJSONObject(i);
            if (e == null || e.optString("title", "").trim().isEmpty() || !validUrl(e.optString("url", ""))) {
                continue;
            }
            support |= "support".equals(e.optString("effect"));
            contradict |= "contradict".equals(e.optString("effect"));
        }
        if (("kept".equals(status) && !support) || ("broken".equals(status) && !contradict)
                || ("partial".equals(status) && !support && !contradict)) {
            return new Result(null, "נדרשת ראיה מקושרת התומכת בסיווג");
        }
        return new Result(value, "מבוסס על הסטטוס והראיות שהוזנו");
    }

    static Member member(JSONObject m) {
        JSONArray promises = m.optJSONArray("promises");
        int total = promises == null ? 0 : promises.length();
        int sum = 0;
        int scored = 0;
        for (int i = 0; i < total; i++) {
            JSONObject p = promises.optJSONObject(i);
            if (p == null) {
                continue;
            }
            Result r = promise(p);
            if (r.value != null) {
                sum += r.value;
                scored++;
            }
        }
        return new Member(scored == 0 ? null : Integer.valueOf(Math.round((float) sum / scored)), scored, total);
    }
}

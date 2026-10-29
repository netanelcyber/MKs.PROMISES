package il.mkpromises;

import java.util.LinkedHashMap;
import java.util.Map;

/** The fixed vocabularies of the data (same keys as the web app). */
final class Vocab {

    static final Map<String, String> STATUS = new LinkedHashMap<>();
    static final Map<String, String> EFFECT = new LinkedHashMap<>();
    static final Map<String, String> ELECTION = new LinkedHashMap<>();
    static final String[] EVIDENCE_TYPES = {"הצעת חוק", "הצבעה", "נאום / התבטאות", "ועדה", "כתבה", "אחר"};

    static {
        STATUS.put("unchecked", "לא נבדק");
        STATUS.put("kept", "קוימה");
        STATUS.put("partial", "קוימה חלקית");
        STATUS.put("broken", "לא קוימה");
        STATUS.put("unmeasurable", "לא ניתנת למדידה");
        STATUS.put("coalition", "הוגבלה בהסכם קואליציוני");
        EFFECT.put("support", "מקדם את ההבטחה");
        EFFECT.put("contradict", "סותר את ההבטחה");
        EFFECT.put("context", "הקשר");
        ELECTION.put("2022", "בחירות 2022 (הכנסת ה-25)");
        ELECTION.put("2026", "בחירות 2026 (הכנסת ה-26)");
        ELECTION.put("other", "אחר");
    }

    private Vocab() {
    }

    static Ui.Field select(Ui.Field f, Map<String, String> options) {
        for (Map.Entry<String, String> e : options.entrySet()) {
            f.option(e.getKey(), e.getValue());
        }
        return f;
    }
}

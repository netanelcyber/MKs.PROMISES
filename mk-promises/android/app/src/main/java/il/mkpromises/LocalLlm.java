package il.mkpromises;

import com.google.mediapipe.tasks.genai.llminference.LlmInference;

import org.json.JSONArray;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Labels (promise, bill title) pairs with a LOCAL language model (about 1B parameters, quantized), via MediaPipe LLM Inference.
 * The model file is provided by the user (a MediaPipe .task / .bin export, for example a Gemma 3 1B build); the
 * app does not download it and nothing leaves the device. The labels join the training set of the small
 * on-device model (the same file as the Gemini labels). A label never changes a promise status.
 * NOT VERIFIED on a device: model format compatibility and speed depend on the phone and the file.
 */
final class LocalLlm {

    private static LlmInference engine;
    private static String enginePath;

    private LocalLlm() {
    }

    static File modelFile(MainActivity app) {
        return new File(app.getFilesDir(), "llm/model.bin");
    }

    static void start(MainActivity app) {
        File f = modelFile(app);
        if (!f.exists()) {
            Ui.confirm(app, "אין מודל שפה מקומי. בחרו קובץ מודל בפורמט MediaPipe LLM (סביבות מיליארד פרמטרים, מכומת), שהורדתם בעצמכם ברישיון שלו. הקובץ גדול (מאות MB) ויועתק למכשיר. לבחור קובץ?",
                    () -> app.pickCopy(f, path -> {
                        Ui.toast(app, "המודל נשמר");
                        start(app);
                    }));
            return;
        }
        List<Ui.Field> fields = new ArrayList<>();
        fields.add(new Ui.Field("n", "כמה זוגות לתייג (איטי: כמה שניות לזוג)", Ui.NUMBER).value("100").required());
        fields.add(new Ui.Field("rm", "להסיר את המודל המקומי מהמכשיר", Ui.CHECK));
        Ui.form(app, "תיוג עם מודל שפה מקומי", "כל החישוב במכשיר ושום דבר לא נשלח החוצה. התיוג הוא אות לאימון, לא אמת.", fields, "תיוג ואימון", v -> {
            if ("true".equals(v.get("rm"))) {
                synchronized (LocalLlm.class) {
                    engine = null;
                }
                f.delete();
                Ui.toast(app, "המודל המקומי הוסר");
                return;
            }
            int n;
            try {
                n = Math.max(10, Math.min(2000, Integer.parseInt(v.get("n"))));
            } catch (NumberFormatException e) {
                Ui.toast(app, "מספר לא תקין");
                return;
            }
            run(app, f, n);
        });
    }

    private static void run(MainActivity app, File model, int n) {
        Ui.toast(app, "טוען את מודל השפה ומתייג…");
        app.background(() -> {
            JSONArray bills = Cache.arr(Cache.read(app, "bills-25.json"), "rows");
            if (bills.length() == 0) {
                bills = BillsScreen.fetchAll(25, c -> {
                });
                Cache.write(app, "bills-25.json", J.obj("knesset", 25, "rows", bills));
            }
            JSONArray saved = Cache.arr(Cache.read(app, GeminiLabel.FILE), "rows");
            Set<String> done = new HashSet<>();
            for (int i = 0; i < saved.length(); i++) {
                done.add(saved.optJSONObject(i).optString("k"));
            }
            List<String[]> pairs = AutoTrain.candidates(app.store.mks(), bills, n, done);
            LlmInference llm = engine(app, model);
            int added = 0;
            for (int i = 0; i < pairs.size(); i++) {
                String[] c = pairs.get(i);
                int label = parse(llm.generateResponse(prompt(c[1], c[2])));
                if (label >= 0) {
                    saved.put(J.obj("k", c[0], "p", c[1], "u", c[2], "l", label, "g", c[3]));
                    added++;
                }
                if (i % 10 == 9) {
                    Cache.write(app, GeminiLabel.FILE, J.obj("rows", saved));
                    final int count = added;
                    final int at = i + 1;
                    app.runOnUiThread(() -> Ui.toast(app, "נבדקו " + at + ", תויגו " + count));
                }
            }
            Cache.write(app, GeminiLabel.FILE, J.obj("rows", saved));
            return added;
        }, added -> {
            Ui.toast(app, "המודל המקומי תייג " + added + " זוגות. מאמן…");
            AutoTrain.run(app);
        }, e -> Ui.toastLong(app, "התיוג המקומי נכשל: " + MainActivity.message(e)));
    }

    private static synchronized LlmInference engine(MainActivity app, File model) {
        if (engine == null || !model.getPath().equals(enginePath)) {
            engine = LlmInference.createFromOptions(app, LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(model.getPath()).setMaxTokens(768).build());
            enginePath = model.getPath();
        }
        return engine;
    }

    static String prompt(String promise, String bill) {
        return "A politician made this election promise:\n" + promise + "\n\nA Knesset bill has this title:\n" + bill
                + "\n\nDoes the bill support the promise, contradict it, or is it only context (related topic or unrelated)? "
                + "Answer with exactly one word: support, contradict, or context.";
    }

    /** First of the three words in the answer, else -1. */
    static int parse(String answer) {
        if (answer == null) {
            return -1;
        }
        String a = answer.toLowerCase(Locale.ROOT);
        int s = a.indexOf("support");
        int c = a.indexOf("contradict");
        int x = a.indexOf("context");
        int best = -1;
        int at = Integer.MAX_VALUE;
        int[] pos = {s, c, x};
        for (int i = 0; i < 3; i++) {
            if (pos[i] >= 0 && pos[i] < at) {
                at = pos[i];
                best = i;
            }
        }
        return best;
    }
}

package il.mkpromises;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native host: a header, a content area, and a stack of screens. No WebView. */
public class MainActivity extends Activity {

    interface Callback<T> {
        void run(T value);
    }

    Store store;
    private final Deque<Screen> stack = new ArrayDeque<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView titleView;
    private FrameLayout content;
    private LinearLayout headerActions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(getFilesDir());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(Color.rgb(0xf4, 0xf6, 0xf8));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(Ui.BRAND);
        int pad = Ui.dp(this, 12);
        header.setPadding(pad, pad, pad, pad);
        titleView = Ui.text(this, "מעקב הבטחות", 20, true, Color.WHITE);
        header.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        headerActions = new LinearLayout(this);
        headerActions.setOrientation(LinearLayout.HORIZONTAL);
        header.addView(headerActions);
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        push(new HomeScreen());
    }

    // ------------------------------------------------------------------ navigation

    void push(Screen s) {
        stack.push(s);
        refresh();
    }

    void pop() {
        if (stack.size() > 1) {
            stack.pop();
            refresh();
        }
    }

    @Override
    public void onBackPressed() {
        if (stack.size() > 1) {
            pop();
        } else {
            super.onBackPressed();
        }
    }

    /** Rebuild the visible screen from the current data. */
    void refresh() {
        Screen s = stack.peek();
        if (s == null) {
            return;
        }
        titleView.setText(s.title());
        headerActions.removeAllViews();
        if (stack.size() > 1) {
            headerActions.addView(headerButton("חזרה", v -> pop()));
        } else {
            headerActions.addView(headerButton("גיבוי", v -> Backup.menu(this)));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        int pad = Ui.dp(this, 12);
        FrameLayout holder = new FrameLayout(this);
        holder.setPadding(pad, pad, pad, pad);
        holder.addView(s.build(this));
        scroll.addView(holder);
        content.removeAllViews();
        content.addView(scroll);
    }

    private View headerButton(String label, View.OnClickListener l) {
        TextView t = Ui.text(this, label, 15, true, Color.WHITE);
        int pad = Ui.dp(this, 8);
        t.setPadding(pad, pad, pad, pad);
        t.setOnClickListener(l);
        return t;
    }

    // ------------------------------------------------------------------ data and background work

    /** Save, and tell the user when saving failed. */
    boolean save() {
        boolean ok = store.save();
        if (!ok) {
            Ui.toastLong(this, "השמירה נכשלה. בדקו מקום פנוי במכשיר");
        }
        return ok;
    }

    /** Run work off the UI thread; deliver the result (or the error) back on it. */
    <T> void background(Callable<T> work, Callback<T> done, Callback<Exception> failed) {
        pool.execute(() -> {
            try {
                final T value = work.call();
                ui.post(() -> {
                    if (!isFinishing()) {
                        done.run(value);
                    }
                });
            } catch (final Exception e) {
                ui.post(() -> {
                    if (!isFinishing()) {
                        failed.run(e);
                    }
                });
            }
        });
    }

    <T> void background(Callable<T> work, Callback<T> done) {
        background(work, done, e -> Ui.toastLong(this, "הפעולה נכשלה: " + message(e)));
    }

    static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private Callback<byte[]> pickCallback;
    private static final int PICK_FILE = 4711;

    /** Let the user pick a text file (a model, a backup) and deliver its content. */
    void pickFile(Callback<String> onText) {
        pickBytes(b -> onText.run(new String(b, java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** Let the user pick any file and deliver its bytes (up to 64 MB). */
    void pickBytes(Callback<byte[]> onBytes) {
        pickCallback = onBytes;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILE || pickCallback == null) {
            return;
        }
        final Callback<byte[]> cb = pickCallback;
        pickCallback = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final android.net.Uri uri = data.getData();
        background(() -> {
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                throw new java.io.IOException("לא ניתן לקרוא את הקובץ");
            }
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 64 * 1024 * 1024) {
                        throw new java.io.IOException("הקובץ גדול מדי");
                    }
                }
                return out.toByteArray();
            } finally {
                in.close();
            }
        }, cb::run);
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    List<JSONObject> membersSorted() {
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < store.mks().length(); i++) {
            JSONObject m = store.mks().optJSONObject(i);
            if (m != null) {
                list.add(m);
            }
        }
        java.util.Collections.sort(list, (a, b) -> a.optString("name").compareTo(b.optString("name")));
        return list;
    }
}

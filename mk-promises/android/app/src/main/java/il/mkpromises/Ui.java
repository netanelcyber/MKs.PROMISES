package il.mkpromises;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** View builders and a small form dialog. Everything is built in code so there are no layout files. */
final class Ui {

    static final int BRAND = Color.rgb(0x1f, 0x4e, 0x79);
    static final int INK = Color.rgb(0x1a, 0x1a, 0x1a);
    static final int MUTE = Color.rgb(0x66, 0x66, 0x66);
    static final int LINE = Color.rgb(0xdd, 0xdd, 0xdd);
    static final int DANGER = Color.rgb(0xb0, 0x1e, 0x1e);

    private Ui() {
    }

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static TextView text(Context c, String s, int sp, boolean bold, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        }
        t.setGravity(Gravity.START);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return t;
    }

    static TextView body(Context c, String s) {
        return text(c, s, 16, false, INK);
    }

    static TextView mute(Context c, String s) {
        return text(c, s, 13, false, MUTE);
    }

    static TextView heading(Context c, String s) {
        TextView t = text(c, s, 19, true, INK);
        t.setPadding(0, dp(c, 14), 0, dp(c, 4));
        return t;
    }

    static Button button(Context c, String label, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    static LinearLayout.LayoutParams match(Context c, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(c, topDp);
        return p;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = vbox(c);
        int pad = dp(c, 12);
        l.setPadding(pad, pad, pad, pad);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setStroke(dp(c, 1), LINE);
        g.setCornerRadius(dp(c, 10));
        l.setBackground(g);
        return l;
    }

    static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }

    static void toastLong(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ forms

    static final int TEXT = 0;
    static final int MULTI = 1;
    static final int URL = 2;
    static final int SELECT = 3;
    static final int CHECK = 4;
    static final int NUMBER = 5;

    static final class Field {
        final String key;
        final String label;
        final int type;
        String value = "";
        boolean required;
        final List<String[]> options = new ArrayList<>();   // {value, label}

        Field(String key, String label, int type) {
            this.key = key;
            this.label = label;
            this.type = type;
        }

        Field value(String v) {
            this.value = v == null ? "" : v;
            return this;
        }

        Field required() {
            this.required = true;
            return this;
        }

        Field option(String v, String l) {
            options.add(new String[]{v, l});
            return this;
        }
    }

    interface FormDone {
        void done(Map<String, String> values);
    }

    /** Shows a dialog with the given fields. Calls done with the values when the user accepts and the required ones are filled. */
    static void form(Context c, String title, String intro, List<Field> fields, String ok, FormDone done) {
        LinearLayout box = vbox(c);
        int pad = dp(c, 16);
        box.setPadding(pad, pad / 2, pad, 0);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        if (intro != null && !intro.isEmpty()) {
            box.addView(mute(c, intro));
        }
        final Map<String, View> views = new HashMap<>();
        for (Field f : fields) {
            if (f.type == CHECK) {
                CheckBox cb = new CheckBox(c);
                cb.setText(f.label);
                cb.setChecked("true".equals(f.value));
                box.addView(cb);
                views.put(f.key, cb);
                continue;
            }
            TextView label = mute(c, f.label + (f.required ? " *" : ""));
            label.setPadding(0, dp(c, 8), 0, 0);
            box.addView(label);
            if (f.type == SELECT) {
                Spinner sp = new Spinner(c);
                List<String> labels = new ArrayList<>();
                int selected = 0;
                for (int i = 0; i < f.options.size(); i++) {
                    labels.add(f.options.get(i)[1]);
                    if (f.options.get(i)[0].equals(f.value)) {
                        selected = i;
                    }
                }
                sp.setAdapter(new ArrayAdapter<>(c, android.R.layout.simple_spinner_dropdown_item, labels));
                sp.setSelection(selected);
                box.addView(sp);
                views.put(f.key, sp);
            } else {
                EditText e = new EditText(c);
                e.setText(f.value);
                e.setGravity(Gravity.START);
                if (f.type == MULTI) {
                    e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                    e.setMinLines(3);
                } else if (f.type == URL) {
                    e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
                } else if (f.type == NUMBER) {
                    e.setInputType(InputType.TYPE_CLASS_NUMBER);
                } else {
                    e.setInputType(InputType.TYPE_CLASS_TEXT);
                }
                box.addView(e);
                views.put(f.key, e);
            }
        }
        ScrollView scroll = new ScrollView(c);
        scroll.addView(box);
        final AlertDialog dialog = new AlertDialog.Builder(c).setTitle(title).setView(scroll)
                .setPositiveButton(ok == null ? "שמירה" : ok, null).setNegativeButton("ביטול", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Map<String, String> out = new HashMap<>();
            for (Field f : fields) {
                View view = views.get(f.key);
                String value;
                if (f.type == CHECK) {
                    value = ((CheckBox) view).isChecked() ? "true" : "";
                } else if (f.type == SELECT) {
                    int i = ((Spinner) view).getSelectedItemPosition();
                    value = i >= 0 && i < f.options.size() ? f.options.get(i)[0] : "";
                } else {
                    value = ((EditText) view).getText().toString().trim();
                }
                if (f.required && value.isEmpty()) {
                    toast(c, "יש למלא: " + f.label);
                    return;
                }
                if (f.type == URL && !value.isEmpty() && !Scoring.validUrl(value)) {
                    toast(c, "הקישור חייב להתחיל ב-http:// או https://");
                    return;
                }
                out.put(f.key, value);
            }
            dialog.dismiss();
            done.done(out);
        }));
        dialog.show();
    }

    static void confirm(Context c, String message, Runnable yes) {
        new AlertDialog.Builder(c).setMessage(message).setPositiveButton("אישור", (d, w) -> yes.run())
                .setNegativeButton("ביטול", null).show();
    }

    static void info(Context c, String title, String message) {
        new AlertDialog.Builder(c).setTitle(title).setMessage(message).setPositiveButton("סגירה", null).show();
    }
}

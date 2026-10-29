package il.mkpromises;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * The stance model, on the device. It scores how a text (an article title, a quote) relates to a promise:
 * support, contradict or context. Two architectures share one file format (see ml/export_js.py):
 *   "hashed": hashed embeddings, BiLSTM, DNN. Trained on a computer with ml/train.py, inference only here.
 *   "light":  hashed embeddings, attention + max pooling, DNN. Trained on the device from the user's labels.
 * Suggestions only: nothing here changes a status.
 */
final class Stance {

    static final String[] LABELS = {"support", "contradict", "context"};
    private static final Pattern WORD = Pattern.compile("[א-תA-Za-z0-9]+");
    private static final String PREFIX = "ובכלמהש";

    static final class Model {
        boolean light;
        boolean synthetic;
        int buckets;
        int dim;
        int hidden;        // light
        int lstmHidden;    // hashed
        int lstmLayers;
        int[] dnn;
        final Map<String, float[]> w = new HashMap<>();
        JSONObject metrics;
    }

    static final class Row {
        final String promise;
        final String utterance;
        final int label;
        final String group;

        Row(String promise, String utterance, int label, String group) {
            this.promise = promise;
            this.utterance = utterance;
            this.label = label;
            this.group = group;
        }
    }

    private static Model current;
    private static boolean loaded;

    private Stance() {
    }

    // ------------------------------------------------------------------ storage

    private static File file(MainActivity app) {
        return new File(app.getFilesDir(), "stance.json");
    }

    static synchronized Model get(MainActivity app) {
        if (!loaded) {
            loaded = true;
            try {
                File f = file(app);
                if (f.exists()) {
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    in.close();
                    current = fromJson(new String(out.toByteArray(), StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                current = null;
            }
        }
        return current;
    }

    static synchronized void set(MainActivity app, Model m) throws Exception {
        FileOutputStream out = new FileOutputStream(file(app));
        try {
            out.write(toJson(m).getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        current = m;
        loaded = true;
    }

    static synchronized void clear(MainActivity app) {
        file(app).delete();
        current = null;
        loaded = true;
    }

    static Model fromJson(String text) throws Exception {
        JSONObject j = new JSONObject(text);
        if (!"mk-promises-stance-1".equals(j.optString("format"))) {
            throw new Exception("קובץ מודל לא תקין");
        }
        JSONObject cfg = j.getJSONObject("cfg");
        Model m = new Model();
        m.light = "light".equals(cfg.optString("arch"));
        m.synthetic = j.optBoolean("synthetic", false);
        m.buckets = cfg.getInt("buckets");
        m.dim = cfg.getInt("dim");
        if (m.light) {
            m.hidden = cfg.getInt("hidden");
        } else {
            m.lstmHidden = cfg.getInt("lstm_hidden");
            m.lstmLayers = cfg.getInt("lstm_layers");
            JSONArray d = cfg.getJSONArray("dnn");
            m.dnn = new int[d.length()];
            for (int i = 0; i < d.length(); i++) {
                m.dnn[i] = d.getInt(i);
            }
        }
        m.metrics = j.optJSONObject("metrics");
        JSONObject tensors = j.getJSONObject("tensors");
        Iterator<String> keys = tensors.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            JSONObject t = tensors.getJSONObject(k);
            byte[] raw = Base64.decode(t.getString("data"), Base64.DEFAULT);
            ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            float[] a;
            if ("f16".equals(t.optString("dtype"))) {
                a = new float[raw.length / 2];
                for (int i = 0; i < a.length; i++) {
                    a[i] = half(bb.getShort(2 * i) & 0xffff);
                }
            } else {
                a = new float[raw.length / 4];
                for (int i = 0; i < a.length; i++) {
                    a[i] = bb.getFloat(4 * i);
                }
            }
            m.w.put(k, a);
        }
        validate(m);
        return m;
    }

    private static void need(Model m, String name, int size) throws Exception {
        float[] a = m.w.get(name);
        if (a == null || a.length != size) {
            throw new Exception("מודל פגום: " + name);
        }
    }

    private static void validate(Model m) throws Exception {
        if (m.buckets < 2 || m.dim < 1 || m.buckets > 1 << 20 || m.dim > 1024) {
            throw new Exception("מודל פגום");
        }
        if (m.light) {
            if (m.hidden < 1 || m.hidden > 4096) {
                throw new Exception("מודל פגום");
            }
            need(m, "emb", m.buckets * m.dim);
            need(m, "seg", 2 * m.dim);
            need(m, "att.w", m.dim);
            need(m, "att.b", 1);
            need(m, "h1.weight", m.hidden * 2 * m.dim);
            need(m, "h1.bias", m.hidden);
            need(m, "h2.weight", 3 * m.hidden);
            need(m, "h2.bias", 3);
        } else {
            int h = m.lstmHidden;
            need(m, "enc.emb.weight", m.buckets * m.dim);
            need(m, "enc.seg.weight", 2 * m.dim);
            for (int l = 0; l < m.lstmLayers; l++) {
                int in = l == 0 ? m.dim : 2 * h;
                for (String sfx : new String[]{"", "_reverse"}) {
                    need(m, "lstm.weight_ih_l" + l + sfx, 4 * h * in);
                    need(m, "lstm.weight_hh_l" + l + sfx, 4 * h * h);
                    need(m, "lstm.bias_ih_l" + l + sfx, 4 * h);
                    need(m, "lstm.bias_hh_l" + l + sfx, 4 * h);
                }
            }
            need(m, "att.weight", 2 * h);
            need(m, "att.bias", 1);
            int in = 4 * h;
            for (int i = 0; i < m.dnn.length; i++) {
                need(m, "head." + 4 * i + ".weight", m.dnn[i] * in);
                need(m, "head." + 4 * i + ".bias", m.dnn[i]);
                need(m, "head." + (4 * i + 2) + ".weight", m.dnn[i]);
                need(m, "head." + (4 * i + 2) + ".bias", m.dnn[i]);
                in = m.dnn[i];
            }
            need(m, "head." + 4 * m.dnn.length + ".weight", 3 * in);
            need(m, "head." + 4 * m.dnn.length + ".bias", 3);
        }
    }

    static String toJson(Model m) throws Exception {
        JSONObject tensors = new JSONObject();
        for (Map.Entry<String, float[]> e : m.w.entrySet()) {
            ByteBuffer bb = ByteBuffer.allocate(e.getValue().length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (float f : e.getValue()) {
                bb.putFloat(f);
            }
            tensors.put(e.getKey(), J.obj("dtype", "f32", "data", Base64.encodeToString(bb.array(), Base64.NO_WRAP)));
        }
        JSONObject cfg = new JSONObject();
        if (m.light) {
            cfg.put("arch", "light").put("buckets", m.buckets).put("dim", m.dim).put("hidden", m.hidden);
        } else {
            cfg.put("buckets", m.buckets).put("dim", m.dim).put("lstm_hidden", m.lstmHidden).put("lstm_layers", m.lstmLayers);
            JSONArray d = new JSONArray();
            for (int x : m.dnn) {
                d.put(x);
            }
            cfg.put("dnn", d);
        }
        JSONObject out = new JSONObject().put("format", "mk-promises-stance-1").put("cfg", cfg).put("synthetic", m.synthetic)
                .put("labels", new JSONArray(Arrays.asList(LABELS))).put("tensors", tensors);
        if (m.metrics != null) {
            out.put("metrics", m.metrics);
        }
        return out.toString();
    }

    private static float half(int h) {
        int s = (h & 0x8000) != 0 ? -1 : 1;
        int e = (h >> 10) & 31;
        int f = h & 1023;
        if (e == 0) {
            return (float) (s * Math.pow(2, -14) * (f / 1024.0));
        }
        if (e == 31) {
            return f != 0 ? Float.NaN : s * Float.POSITIVE_INFINITY;
        }
        return (float) (s * Math.pow(2, e - 15) * (1 + f / 1024.0));
    }

    // ------------------------------------------------------------------ features

    static long crc(String s) {
        CRC32 c = new CRC32();
        c.update(s.getBytes(StandardCharsets.UTF_8));
        return c.getValue();
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(s.toLowerCase(Locale.ROOT));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    static int[] feats(String tok, int buckets, int maxK) {
        List<String> f = new ArrayList<>();
        f.add("w:" + tok);
        for (int k = 1; k <= 2; k++) {
            if (tok.length() - k >= 2) {
                boolean ok = true;
                for (int i = 0; i < k; i++) {
                    if (PREFIX.indexOf(tok.charAt(i)) < 0) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    f.add("p:" + tok.substring(k));
                }
            }
        }
        String w = "<" + tok + ">";
        for (int i = 0; i + 3 <= w.length(); i++) {
            f.add("c:" + w.substring(i, i + 3));
        }
        int n = Math.min(maxK, f.size());
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = (int) (crc(f.get(i)) % (buckets - 1)) + 1;
        }
        return out;
    }

    /** Token features and segment ids of "promise [sep] utterance". */
    private static final class Seq {
        int[][] ids;
        int[] seg;
    }

    private static Seq sequence(Model m, String promise, String utterance) {
        List<String> p = tokens(promise);
        List<String> u = tokens(utterance);
        if (p.size() > 40) {
            p = p.subList(0, 40);
        }
        if (u.size() > 160) {
            u = u.subList(0, 160);
        }
        List<String> all = new ArrayList<>(p);
        all.add("[sep]");
        all.addAll(u);
        Seq s = new Seq();
        s.ids = new int[all.size()][];
        s.seg = new int[all.size()];
        for (int i = 0; i < all.size(); i++) {
            s.ids[i] = feats(all.get(i), m.buckets, 14);
            s.seg[i] = i <= p.size() ? 0 : 1;
        }
        return s;
    }

    private static float[][] embed(Model m, Seq s, float[] emb, float[] seg) {
        int dim = m.dim;
        float[][] xs = new float[s.ids.length][dim];
        for (int t = 0; t < xs.length; t++) {
            int[] ids = s.ids[t];
            for (int id : ids) {
                int row = id * dim;
                for (int d = 0; d < dim; d++) {
                    xs[t][d] += emb[row + d];
                }
            }
            for (int d = 0; d < dim; d++) {
                xs[t][d] = xs[t][d] / ids.length + seg[s.seg[t] * dim + d];
            }
        }
        return xs;
    }

    // ------------------------------------------------------------------ math

    private static float erf(float x0) {
        double x = x0;
        double sign = x < 0 ? -1 : 1;
        x = Math.abs(x);
        double t = 1 / (1 + 0.3275911 * x);
        return (float) (sign * (1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x)));
    }

    private static float gelu(float x) {
        return 0.5f * x * (1 + erf((float) (x / Math.sqrt(2))));
    }

    private static float geluGrad(float x) {
        return 0.5f * (1 + erf((float) (x / Math.sqrt(2)))) + (float) (x * Math.exp(-x * x / 2) / Math.sqrt(2 * Math.PI));
    }

    private static float sigmoid(float x) {
        return (float) (1 / (1 + Math.exp(-x)));
    }

    private static float[] linear(float[] w, float[] b, float[] x) {
        int n = b.length;
        int d = x.length;
        float[] o = new float[n];
        for (int i = 0; i < n; i++) {
            float s = b[i];
            int r = i * d;
            for (int j = 0; j < d; j++) {
                s += w[r + j] * x[j];
            }
            o[i] = s;
        }
        return o;
    }

    private static float[] layerNorm(float[] g, float[] b, float[] x) {
        int n = x.length;
        float mean = 0;
        for (float v : x) {
            mean += v;
        }
        mean /= n;
        float var = 0;
        for (float v : x) {
            var += (v - mean) * (v - mean);
        }
        var /= n;
        float[] o = new float[n];
        for (int i = 0; i < n; i++) {
            o[i] = (x[i] - mean) / (float) Math.sqrt(var + 1e-5) * g[i] + b[i];
        }
        return o;
    }

    private static float[] softmax(float[] z) {
        float mx = Float.NEGATIVE_INFINITY;
        for (float v : z) {
            mx = Math.max(mx, v);
        }
        float sum = 0;
        float[] e = new float[z.length];
        for (int i = 0; i < z.length; i++) {
            e[i] = (float) Math.exp(z[i] - mx);
            sum += e[i];
        }
        for (int i = 0; i < z.length; i++) {
            e[i] /= sum;
        }
        return e;
    }

    // ------------------------------------------------------------------ inference

    /** Probabilities of support, contradict, context. */
    static float[] score(Model m, String promise, String utterance) {
        Seq s = sequence(m, promise, utterance);
        return m.light ? lightForward(m, s, embed(m, s, m.w.get("emb"), m.w.get("seg")), null).probs : lstmScore(m, s);
    }

    private static float[] lstmScore(Model m, Seq s) {
        int h = m.lstmHidden;
        float[][] seq = embed(m, s, m.w.get("enc.emb.weight"), m.w.get("enc.seg.weight"));
        for (int layer = 0; layer < m.lstmLayers; layer++) {
            float[][] f = lstmDir(m, layer, false, seq, h);
            float[][] b = lstmDir(m, layer, true, seq, h);
            float[][] next = new float[seq.length][2 * h];
            for (int t = 0; t < seq.length; t++) {
                System.arraycopy(f[t], 0, next[t], 0, h);
                System.arraycopy(b[t], 0, next[t], h, h);
            }
            seq = next;
        }
        int T = seq.length;
        float[] aw = m.w.get("att.weight");
        float ab = m.w.get("att.bias")[0];
        float[] a = new float[T];
        for (int i = 0; i < T; i++) {
            float sc = ab;
            for (int j = 0; j < 2 * h; j++) {
                sc += aw[j] * seq[i][j];
            }
            a[i] = sc;
        }
        a = softmax(a);
        float[] x = new float[4 * h];
        Arrays.fill(x, 2 * h, 4 * h, Float.NEGATIVE_INFINITY);
        for (int i = 0; i < T; i++) {
            for (int j = 0; j < 2 * h; j++) {
                x[j] += a[i] * seq[i][j];
                x[2 * h + j] = Math.max(x[2 * h + j], seq[i][j]);
            }
        }
        for (int i = 0; i < m.dnn.length; i++) {
            x = linear(m.w.get("head." + 4 * i + ".weight"), m.w.get("head." + 4 * i + ".bias"), x);
            for (int j = 0; j < x.length; j++) {
                x[j] = gelu(x[j]);
            }
            x = layerNorm(m.w.get("head." + (4 * i + 2) + ".weight"), m.w.get("head." + (4 * i + 2) + ".bias"), x);
        }
        String last = "head." + 4 * m.dnn.length;
        return softmax(linear(m.w.get(last + ".weight"), m.w.get(last + ".bias"), x));
    }

    private static float[][] lstmDir(Model m, int layer, boolean rev, float[][] seq, int H) {
        String sfx = "_l" + layer + (rev ? "_reverse" : "");
        float[] wi = m.w.get("lstm.weight_ih" + sfx);
        float[] wh = m.w.get("lstm.weight_hh" + sfx);
        float[] bi = m.w.get("lstm.bias_ih" + sfx);
        float[] bh = m.w.get("lstm.bias_hh" + sfx);
        int T = seq.length;
        int D = seq[0].length;
        float[] h = new float[H];
        float[] c = new float[H];
        float[][] out = new float[T][];
        float[] g = new float[4 * H];
        for (int step = 0; step < T; step++) {
            int t = rev ? T - 1 - step : step;
            float[] x = seq[t];
            for (int r = 0; r < 4 * H; r++) {
                float sum = bi[r] + bh[r];
                int ri = r * D;
                int rh = r * H;
                for (int j = 0; j < D; j++) {
                    sum += wi[ri + j] * x[j];
                }
                for (int j = 0; j < H; j++) {
                    sum += wh[rh + j] * h[j];
                }
                g[r] = sum;
            }
            float[] nh = new float[H];
            for (int u = 0; u < H; u++) {
                float ig = sigmoid(g[u]);
                float fg = sigmoid(g[H + u]);
                float gg = (float) Math.tanh(g[2 * H + u]);
                float og = sigmoid(g[3 * H + u]);
                c[u] = fg * c[u] + ig * gg;
                nh[u] = og * (float) Math.tanh(c[u]);
            }
            h = nh;
            out[t] = nh;
        }
        return out;
    }

    // ------------------------------------------------------------------ the light architecture

    private static final class Fwd {
        float[] alpha;
        float[] z;
        int[] arg;
        float[] h;
        float[] g;
        float[] probs;
    }

    private static Fwd lightForward(Model m, Seq s, float[][] xs, Object unused) {
        int dim = m.dim;
        int T = xs.length;
        float[] aw = m.w.get("att.w");
        float ab = m.w.get("att.b")[0];
        Fwd f = new Fwd();
        float[] a = new float[T];
        for (int i = 0; i < T; i++) {
            float sc = ab;
            for (int d = 0; d < dim; d++) {
                sc += aw[d] * xs[i][d];
            }
            a[i] = sc;
        }
        f.alpha = softmax(a);
        f.z = new float[2 * dim];
        f.arg = new int[dim];
        for (int d = 0; d < dim; d++) {
            float best = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < T; i++) {
                f.z[d] += f.alpha[i] * xs[i][d];
                if (xs[i][d] > best) {
                    best = xs[i][d];
                    f.arg[d] = i;
                }
            }
            f.z[dim + d] = best;
        }
        f.h = linear(m.w.get("h1.weight"), m.w.get("h1.bias"), f.z);
        f.g = new float[f.h.length];
        for (int i = 0; i < f.h.length; i++) {
            f.g[i] = gelu(f.h[i]);
        }
        f.probs = softmax(linear(m.w.get("h2.weight"), m.w.get("h2.bias"), f.g));
        return f;
    }

    /** Gradients: dense arrays by tensor name, and a sparse map of embedding rows. */
    private static final class Grads {
        final Map<String, float[]> dense = new HashMap<>();
        final Map<Integer, float[]> emb = new HashMap<>();

        Grads(Model m) {
            for (Map.Entry<String, float[]> e : m.w.entrySet()) {
                if (!e.getKey().equals("emb")) {
                    dense.put(e.getKey(), new float[e.getValue().length]);
                }
            }
        }
    }

    private static float backward(Model m, Seq s, int y, float weight, Grads g) {
        int dim = m.dim;
        int H = m.hidden;
        float[][] xs = embed(m, s, m.w.get("emb"), m.w.get("seg"));
        Fwd f = lightForward(m, s, xs, null);
        int T = xs.length;
        float[] dl = new float[3];
        for (int j = 0; j < 3; j++) {
            dl[j] = (f.probs[j] - (j == y ? 1 : 0)) * weight;
        }
        float[] w2 = m.w.get("h2.weight");
        float[] gw2 = g.dense.get("h2.weight");
        float[] gb2 = g.dense.get("h2.bias");
        float[] dg = new float[H];
        for (int j = 0; j < 3; j++) {
            gb2[j] += dl[j];
            for (int i = 0; i < H; i++) {
                gw2[j * H + i] += dl[j] * f.g[i];
                dg[i] += w2[j * H + i] * dl[j];
            }
        }
        float[] w1 = m.w.get("h1.weight");
        float[] gw1 = g.dense.get("h1.weight");
        float[] gb1 = g.dense.get("h1.bias");
        float[] dz = new float[2 * dim];
        for (int i = 0; i < H; i++) {
            float dh = dg[i] * geluGrad(f.h[i]);
            gb1[i] += dh;
            for (int d = 0; d < 2 * dim; d++) {
                gw1[i * 2 * dim + d] += dh * f.z[d];
                dz[d] += w1[i * 2 * dim + d] * dh;
            }
        }
        float[][] dx = new float[T][dim];
        float[] dAlpha = new float[T];
        float sumAD = 0;
        for (int i = 0; i < T; i++) {
            float acc = 0;
            for (int d = 0; d < dim; d++) {
                acc += xs[i][d] * dz[d];
                dx[i][d] += f.alpha[i] * dz[d];
            }
            dAlpha[i] = acc;
            sumAD += f.alpha[i] * acc;
        }
        float[] aw = m.w.get("att.w");
        float[] gaw = g.dense.get("att.w");
        float[] gab = g.dense.get("att.b");
        for (int i = 0; i < T; i++) {
            float da = f.alpha[i] * (dAlpha[i] - sumAD);
            gab[0] += da;
            for (int d = 0; d < dim; d++) {
                gaw[d] += da * xs[i][d];
                dx[i][d] += da * aw[d];
            }
        }
        for (int d = 0; d < dim; d++) {
            dx[f.arg[d]][d] += dz[dim + d];
        }
        float[] gseg = g.dense.get("seg");
        for (int i = 0; i < T; i++) {
            int[] ids = s.ids[i];
            int sb = s.seg[i] * dim;
            for (int d = 0; d < dim; d++) {
                gseg[sb + d] += dx[i][d];
            }
            for (int id : ids) {
                float[] row = g.emb.get(id);
                if (row == null) {
                    row = new float[dim];
                    g.emb.put(id, row);
                }
                for (int d = 0; d < dim; d++) {
                    row[d] += dx[i][d] / ids.length;
                }
            }
        }
        return (float) (-Math.log(Math.max(f.probs[y], 1e-12)) * weight);
    }

    // ------------------------------------------------------------------ training

    interface Progress {
        void onEpoch(int epoch, float loss, float accuracy);
    }

    static final class Result {
        Model model;
        float accuracy;
        float macroF1;
        int trainSize;
        int valSize;
    }

    static Model init(int buckets, int dim, int hidden, long seed) {
        Random rng = new Random(seed);
        Model m = new Model();
        m.light = true;
        m.buckets = buckets;
        m.dim = dim;
        m.hidden = hidden;
        m.w.put("emb", gauss(rng, buckets * dim, 0.1f));
        m.w.put("seg", gauss(rng, 2 * dim, 0.1f));
        m.w.put("att.w", gauss(rng, dim, 0.1f));
        m.w.put("att.b", new float[1]);
        m.w.put("h1.weight", gauss(rng, hidden * 2 * dim, (float) Math.sqrt(2.0 / (2 * dim))));
        m.w.put("h1.bias", new float[hidden]);
        m.w.put("h2.weight", gauss(rng, 3 * hidden, (float) Math.sqrt(2.0 / hidden)));
        m.w.put("h2.bias", new float[3]);
        return m;
    }

    private static float[] gauss(Random rng, int n, float scale) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            a[i] = (float) rng.nextGaussian() * scale;
        }
        return a;
    }

    static Model copy(Model m) {
        Model c = new Model();
        c.light = m.light;
        c.buckets = m.buckets;
        c.dim = m.dim;
        c.hidden = m.hidden;
        for (Map.Entry<String, float[]> e : m.w.entrySet()) {
            c.w.put(e.getKey(), e.getValue().clone());
        }
        return c;
    }

    /** {accuracy, macroF1} of the model on the rows. */
    static float[] evaluate(Model m, List<Row> rows) {
        int[][] cm = new int[3][3];
        int ok = 0;
        for (Row r : rows) {
            float[] p = score(m, r.promise, r.utterance);
            int k = 0;
            for (int i = 1; i < 3; i++) {
                if (p[i] > p[k]) {
                    k = i;
                }
            }
            cm[r.label][k]++;
            if (k == r.label) {
                ok++;
            }
        }
        float f1 = 0;
        int n = 0;
        for (int c = 0; c < 3; c++) {
            int tp = cm[c][c];
            int fp = cm[0][c] + cm[1][c] + cm[2][c] - tp;
            int fn = cm[c][0] + cm[c][1] + cm[c][2] - tp;
            if (tp + fn > 0) {
                f1 += 2f * tp / Math.max(2 * tp + fp + fn, 1);
                n++;
            }
        }
        return new float[]{rows.isEmpty() ? 0 : (float) ok / rows.size(), n == 0 ? 0 : f1 / n};
    }

    /** Held-out split: whole members go to validation when there are at least five of them, otherwise every fifth row. */
    static void split(List<Row> rows, List<Row> train, List<Row> val) {
        Set<String> groups = new HashSet<>();
        for (Row r : rows) {
            groups.add(r.group);
        }
        if (groups.size() >= 5) {
            List<String> sorted = new ArrayList<>(groups);
            java.util.Collections.sort(sorted, (a, b) -> Long.compare(crc(a), crc(b)));
            Set<String> held = new HashSet<>(sorted.subList(0, Math.max(1, Math.round(sorted.size() * 0.2f))));
            for (Row r : rows) {
                (held.contains(r.group) ? val : train).add(r);
            }
        } else {
            for (int i = 0; i < rows.size(); i++) {
                (i % 5 == 0 ? val : train).add(rows.get(i));
            }
        }
    }

    static Result train(List<Row> rows, int epochs, Progress progress, long seed) {
        List<Row> tr = new ArrayList<>();
        List<Row> val = new ArrayList<>();
        split(rows, tr, val);
        Model model = init(4096, 32, 48, seed);
        Random rng = new Random(seed + 7);
        int[] counts = new int[3];
        for (Row r : tr) {
            counts[r.label]++;
        }
        float[] cw = new float[3];
        for (int i = 0; i < 3; i++) {
            cw[i] = counts[i] == 0 ? 0 : (float) tr.size() / (3 * counts[i]);
        }
        Map<String, float[]> mom = new HashMap<>();
        Map<String, float[]> vel = new HashMap<>();
        for (Map.Entry<String, float[]> e : model.w.entrySet()) {
            mom.put(e.getKey(), new float[e.getValue().length]);
            vel.put(e.getKey(), new float[e.getValue().length]);
        }
        List<Seq> cache = new ArrayList<>();
        for (Row r : tr) {
            cache.add(sequence(model, r.promise, r.utterance));
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < tr.size(); i++) {
            order.add(i);
        }
        Model best = null;
        float bestF1 = -1;
        int bad = 0;
        int step = 0;
        float lr = 0.01f;
        int batch = 16;
        int epoch = 0;
        while (epoch < epochs) {
            java.util.Collections.shuffle(order, rng);
            float loss = 0;
            for (int b = 0; b < order.size(); b += batch) {
                Grads g = new Grads(model);
                int end = Math.min(b + batch, order.size());
                for (int k = b; k < end; k++) {
                    int idx = order.get(k);
                    loss += backward(model, cache.get(idx), tr.get(idx).label, cw[tr.get(idx).label], g);
                }
                step++;
                float n = end - b;
                float c1 = 1 - (float) Math.pow(0.9, step);
                float c2 = 1 - (float) Math.pow(0.999, step);
                for (Map.Entry<String, float[]> e : g.dense.entrySet()) {
                    adam(model.w.get(e.getKey()), e.getValue(), 0, mom.get(e.getKey()), vel.get(e.getKey()), 0, n, lr, c1, c2);
                }
                for (Map.Entry<Integer, float[]> e : g.emb.entrySet()) {
                    adam(model.w.get("emb"), e.getValue(), 0, mom.get("emb"), vel.get("emb"), e.getKey() * model.dim, n, lr, c1, c2);
                }
            }
            epoch++;
            float[] metrics = evaluate(model, val.isEmpty() ? tr : val);
            if (metrics[1] > bestF1) {
                bestF1 = metrics[1];
                best = copy(model);
                bad = 0;
            } else {
                bad++;
            }
            if (progress != null) {
                progress.onEpoch(epoch, loss / Math.max(tr.size(), 1), metrics[0]);
            }
            if (bad >= 5) {
                break;
            }
        }
        Result r = new Result();
        r.model = best == null ? model : best;
        float[] fin = evaluate(r.model, val.isEmpty() ? tr : val);
        r.accuracy = fin[0];
        r.macroF1 = fin[1];
        r.trainSize = tr.size();
        r.valSize = val.size();
        r.model.metrics = J.obj("accuracy", fin[0], "macroF1", fin[1], "trainSize", tr.size(), "valSize", val.size());
        return r;
    }

    private static void adam(float[] w, float[] g, int gOffset, float[] m, float[] v, int wOffset, float n, float lr, float c1, float c2) {
        for (int i = 0; i < g.length; i++) {
            int idx = wOffset + i;
            float gi = g[gOffset + i] / n;
            m[idx] = 0.9f * m[idx] + 0.1f * gi;
            v[idx] = 0.999f * v[idx] + 0.001f * gi * gi;
            w[idx] -= lr * (m[idx] / c1) / ((float) Math.sqrt(v[idx] / c2) + 1e-8f);
        }
    }

    // ------------------------------------------------------------------ use in the screens

    /** "Model suggestion: ..." for a text against the member's promises, or null (no model, or nothing above 60%). */
    static String hint(MainActivity app, JSONObject member, String title) {
        Model m = get(app);
        JSONArray promises = member.optJSONArray("promises");
        if (m == null || promises == null) {
            return null;
        }
        float bestConf = 0;
        String bestLabel = null;
        String bestPromise = "";
        for (int i = 0; i < promises.length(); i++) {
            String text = promises.optJSONObject(i).optString("text");
            float[] p = score(m, text, title);
            int k = 0;
            for (int j = 1; j < 3; j++) {
                if (p[j] > p[k]) {
                    k = j;
                }
            }
            if (k < 2 && p[k] >= 0.6f && p[k] > bestConf) {
                bestConf = p[k];
                bestLabel = k == 0 ? "תומך" : "סותר";
                bestPromise = text.substring(0, Math.min(30, text.length()));
            }
        }
        if (bestLabel == null) {
            return null;
        }
        return (m.synthetic ? "הצעת דמו (סינתטי, לא אמין): " : "הצעת מודל: ") + bestLabel + " " + Math.round(bestConf * 100) + "% · " + bestPromise;
    }
}

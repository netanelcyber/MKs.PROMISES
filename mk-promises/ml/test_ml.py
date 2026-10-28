"""Pipeline tests. They use synthetic data and a randomly initialised tiny BERT,
so they check that the code runs and is wired correctly, not that it is accurate.

  python test_ml.py
"""
import json
import os
import subprocess
import sys
import tempfile

import torch

from model import LABELS, build, load_checkpoint
from protocol_parser import chunk_text, match_mk, parse_protocol
from synthetic import make_protocol, make_synthetic

HERE = os.path.dirname(os.path.abspath(__file__))
fails = 0


def ok(cond, msg):
    global fails
    print(("ok:   " if cond else "FAIL: ") + msg)
    fails += 0 if cond else 1


def run(*args):
    return subprocess.run([sys.executable, *args], cwd=HERE, capture_output=True, text=True)


# ---- parser
txt = '''נכחו:
<< יור >> היו"ר אבי כהן: << יור >>
אני פותח את הישיבה. נעבור לסעיף הראשון.
חה"כ דנה לוי (מפלגה א'):
אני תומכת בהצעה.
זו עמדה שלי.
השר יוסי מזרחי:
אני מתנגד.
'''
u = parse_protocol(txt)
ok([x.speaker for x in u] == ["אבי כהן", "דנה לוי", "יוסי מזרחי"], f"speakers parsed and cleaned: {[x.speaker for x in u]}")
ok(u[0].is_chair and not u[1].is_chair, "chair detected")
ok(u[1].text == "אני תומכת בהצעה. זו עמדה שלי.", "multi-line speech joined")
ok(match_mk("דנה לוי", ["דנה לוי", "רות שמש"]) == "דנה לוי", "exact MK match")
ok(match_mk("לוי", ["דנה לוי", "רות שמש"]) is None, "single-token surname alone is not matched")
ok(all(len(c.split()) <= 12 for c in chunk_text("א ב ג ד ה. ו ז ח ט י. כ ל מ נ ס.", 8)), "chunking respects max words")

# ---- hashed model: shapes, gradients, padding invariance
cfg = {"encoder": "hashed", "hf_model": "", "freeze": True, "lstm_hidden": 32, "lstm_layers": 2, "dnn": [32, 16],
       "dropout": 0.3, "dim": 32, "buckets": 4096}
model, coll = build(cfg)
rows = make_synthetic(8, seed=1)
b = coll(rows)
y = b.pop("y")
model.train()
out = model(b)
ok(tuple(out.shape) == (8, 3), f"logits shape {tuple(out.shape)}")
torch.nn.functional.cross_entropy(out, y).backward()
ok(all(p.grad is not None for n, p in model.named_parameters() if n.startswith(("lstm", "head", "att"))), "gradients reach LSTM and DNN")
model.eval()
with torch.no_grad():
    alone = model(coll(rows[:1]) | {})
    batch = coll(rows)
    batch.pop("y")
    together = model(batch)
ok(torch.allclose(alone[0], together[0], atol=1e-4), "padding does not change a row's output")

# ---- tiny local BERT through the HF code path
try:
    import transformers  # noqa: F401
    from transformers import BertConfig, BertModel, BertTokenizerFast
    d = tempfile.mkdtemp()
    chars = list("אבגדהוזחטיכלמנסעפצקרשתךםןףץ")
    vocab = ["[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]"] + chars + ["##" + c for c in chars]
    open(os.path.join(d, "vocab.txt"), "w", encoding="utf-8").write("\n".join(vocab))
    BertTokenizerFast(os.path.join(d, "vocab.txt")).save_pretrained(d)
    BertModel(BertConfig(vocab_size=len(vocab), hidden_size=32, num_hidden_layers=2, num_attention_heads=2,
                         intermediate_size=64)).save_pretrained(d)
    hcfg = dict(cfg, encoder="hf", hf_model=d, local_only=True, max_len=64)
    hm, hc = build(hcfg)
    hb = hc(rows)
    hy = hb.pop("y")
    ho = hm.train()(hb)
    ho.sum().backward()
    ok(tuple(ho.shape) == (8, 3), "HF path: logits shape")
    ok(all(not p.requires_grad for p in hm.enc.parameters()), "HF encoder frozen by default")
    ok(any(p.grad is not None for p in hm.lstm.parameters()), "HF path: gradients reach the LSTM")
    r = run("train.py", "--synthetic", "120", "--encoder", "hf", "--hf-model", d, "--local-only", "--epochs", "1",
            "--lstm-hidden", "16", "--out", os.path.join(d, "run"))
    ok(r.returncode == 0 and os.path.exists(os.path.join(d, "run", "model.pt")), "train.py runs with the HF encoder" + ("" if r.returncode == 0 else "\n" + r.stderr[-800:]))
    m2, _, _ = load_checkpoint(os.path.join(d, "run", "model.pt"))
    ok(m2 is not None, "HF checkpoint (frozen LLM excluded) reloads")
except ImportError:
    print("skip: transformers not installed, HF path not tested")

# ---- train + predict end to end on synthetic data
out = tempfile.mkdtemp()
r = run("train.py", "--synthetic", "900", "--encoder", "hashed", "--epochs", "20", "--lstm-hidden", "48", "--out", out)
ok(r.returncode == 0, "train.py runs with the hashed encoder" + ("" if r.returncode == 0 else "\n" + r.stderr[-800:]))
if r.returncode == 0:
    m = json.load(open(os.path.join(out, "metrics.json")))
    ok(m["macro_f1"] > 0.9 and m["synthetic"], f"learns the synthetic templates (test macro-F1 {m['macro_f1']:.3f}; this is NOT a real-world score)")
    prow = make_synthetic(6, seed=5)
    promise = "להגדיל את תקציב הבריאות"
    sp = ["דנה לוי", "רות שמש", "דנה לוי", "רות שמש", "דנה לוי", "רות שמש"]
    for i, rr in enumerate(prow):
        rr["utterance"] = ["אני תומך בהגדלת התקציב של הבריאות ואפעל לכך בוועדה", "אני מתנגד להגדלת התקציב של הבריאות ויש לקצץ בו"][i % 2]
    pf = os.path.join(out, "p.txt")
    open(pf, "w", encoding="utf-8").write(make_protocol(prow, sp))
    r = run("predict.py", "--checkpoint", os.path.join(out, "model.pt"), "--protocol", pf, "--promise", promise)
    lines = [json.loads(x) for x in r.stdout.splitlines() if x.strip()]
    ok(r.returncode == 0 and len(lines) >= 4, f"predict.py emits suggestions ({len(lines)} lines)")
    ok(any(x["suggested"] == "support" for x in lines) and any(x["suggested"] == "contradict" for x in lines), "both support and contradict suggested")
    ok(all(not x["is_chair"] for x in lines), "chair opening remarks are not flagged")

print("ALL PASSED" if not fails else f"{fails} FAILURES")
sys.exit(1 if fails else 0)

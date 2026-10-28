"""Train StanceNet on labelled (promise, utterance) pairs.

Data: JSONL, one object per line:
  {"promise": "...", "utterance": "...", "label": "support|contradict|context", "group": "<mk name>"}
`group` is optional. When present, an MK's rows all land on the same side of the
train/val/test split, so the model is not scored on speakers it trained on.

Examples:
  python train.py --data labelled.jsonl --encoder hf --hf-model dicta-il/dictabert --out run1
  python train.py --synthetic 600 --encoder hashed --out smoke     # pipeline test only
"""
import argparse
import copy
import json
import os
import random

import numpy as np
import torch
import torch.nn as nn
from torch.utils.data import DataLoader

from model import LABELS, build, save_checkpoint


def read_jsonl(path):
    with open(path, encoding="utf-8") as f:
        rows = [json.loads(x) for x in f if x.strip()]
    for r in rows:
        if r.get("label") not in LABELS:
            raise SystemExit(f"bad label {r.get('label')!r}; expected one of {LABELS}")
    return rows


def split_by_group(rows, seed, frac=(0.7, 0.15)):
    groups = sorted({r.get("group", f"row{i}") for i, r in enumerate(rows)})
    random.Random(seed).shuffle(groups)
    a, b = int(len(groups) * frac[0]), int(len(groups) * (frac[0] + frac[1]))
    side = {g: 0 if i < a else 1 if i < b else 2 for i, g in enumerate(groups)}
    parts = ([], [], [])
    for i, r in enumerate(rows):
        parts[side[r.get("group", f"row{i}")]].append(r)
    return parts


def metrics(y, p, n=len(LABELS)):
    cm = np.zeros((n, n), dtype=int)
    for a, b in zip(y, p):
        cm[a, b] += 1
    per = {}
    for c in range(n):
        tp, fp, fn = cm[c, c], cm[:, c].sum() - cm[c, c], cm[c, :].sum() - cm[c, c]
        pr, rc = tp / max(tp + fp, 1), tp / max(tp + fn, 1)
        per[LABELS[c]] = {"precision": pr, "recall": rc, "f1": 2 * pr * rc / max(pr + rc, 1e-9), "support": int(cm[c].sum())}
    return {"accuracy": float(np.trace(cm) / max(cm.sum(), 1)),
            "macro_f1": float(np.mean([v["f1"] for v in per.values()])),
            "per_class": per, "confusion": cm.tolist(), "labels": LABELS}


@torch.no_grad()
def predict(model, loader, device):
    model.eval()
    ys, ps = [], []
    for b in loader:
        y = b.pop("y")
        b = {k: v.to(device) for k, v in b.items()}
        ys += y.tolist()
        ps += model(b).argmax(-1).cpu().tolist()
    return ys, ps


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data")
    ap.add_argument("--synthetic", type=int, default=0, help="use N template rows (pipeline test only)")
    ap.add_argument("--encoder", choices=["hashed", "hf"], default="hashed")
    ap.add_argument("--hf-model", default="dicta-il/dictabert")
    ap.add_argument("--local-only", action="store_true", help="load the HF model from disk only")
    ap.add_argument("--unfreeze", action="store_true", help="fine-tune the LLM as well")
    ap.add_argument("--epochs", type=int, default=15)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--llm-lr", type=float, default=2e-5)
    ap.add_argument("--lstm-hidden", type=int, default=128)
    ap.add_argument("--lstm-layers", type=int, default=2)
    ap.add_argument("--dim", type=int, default=128)
    ap.add_argument("--buckets", type=int, default=2 ** 16)
    ap.add_argument("--dropout", type=float, default=0.3)
    ap.add_argument("--patience", type=int, default=3)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--out", default="run")
    a = ap.parse_args()

    random.seed(a.seed); np.random.seed(a.seed); torch.manual_seed(a.seed)
    if a.synthetic:
        from synthetic import make_synthetic
        rows = make_synthetic(a.synthetic, seed=a.seed)
        print("WARNING: SYNTHETIC TEMPLATE DATA. The metrics below say nothing about real protocols.")
    elif a.data:
        rows = read_jsonl(a.data)
    else:
        raise SystemExit("give --data FILE.jsonl or --synthetic N")

    cfg = {"encoder": a.encoder, "hf_model": a.hf_model, "local_only": a.local_only, "freeze": not a.unfreeze,
           "lstm_hidden": a.lstm_hidden, "lstm_layers": a.lstm_layers, "dnn": [256, 128], "dropout": a.dropout,
           "dim": a.dim, "buckets": a.buckets, "max_len": 256, "synthetic": bool(a.synthetic)}
    model, coll = build(cfg)
    device = "cuda" if torch.cuda.is_available() else "cpu"
    model.to(device)

    tr, va, te = split_by_group(rows, a.seed)
    if min(len(tr), len(va), len(te)) == 0:
        raise SystemExit(f"split too small: {len(tr)}/{len(va)}/{len(te)}; add data or groups")
    print(f"train {len(tr)}  val {len(va)}  test {len(te)}  device {device}")
    mk = lambda d, sh: DataLoader(d, batch_size=a.batch, shuffle=sh, collate_fn=coll)
    ltr, lva, lte = mk(tr, True), mk(va, False), mk(te, False)

    cnt = np.bincount([LABELS.index(r["label"]) for r in tr], minlength=len(LABELS)) + 1
    w = torch.tensor(cnt.sum() / (len(LABELS) * cnt), dtype=torch.float, device=device)
    loss_fn = nn.CrossEntropyLoss(weight=w)
    llm = [p for n, p in model.named_parameters() if n.startswith("enc.m.") and p.requires_grad]
    rest = [p for n, p in model.named_parameters() if not n.startswith("enc.m.") and p.requires_grad]
    opt = torch.optim.AdamW([{"params": rest, "lr": a.lr}] + ([{"params": llm, "lr": a.llm_lr}] if llm else []), weight_decay=0.01)

    best, best_state, bad = -1.0, None, 0
    for ep in range(1, a.epochs + 1):
        model.train()
        tot = 0.0
        for b in ltr:
            y = b.pop("y").to(device)
            b = {k: v.to(device) for k, v in b.items()}
            opt.zero_grad()
            loss = loss_fn(model(b), y)
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            tot += loss.item() * len(y)
        yv, pv = predict(model, lva, device)
        f1 = metrics(yv, pv)["macro_f1"]
        print(f"epoch {ep:2d}  loss {tot / len(tr):.4f}  val macro-F1 {f1:.3f}")
        if f1 > best:
            best, bad = f1, 0
            best_state = copy.deepcopy({k: v for k, v in model.state_dict().items()})
        else:
            bad += 1
            if bad >= a.patience:
                break

    model.load_state_dict(best_state)
    yt, pt = predict(model, lte, device)
    m = metrics(yt, pt)
    m["val_macro_f1"] = best
    m["synthetic"] = bool(a.synthetic)
    print(json.dumps({k: m[k] for k in ("accuracy", "macro_f1", "confusion")}, ensure_ascii=False))
    os.makedirs(a.out, exist_ok=True)
    save_checkpoint(model, cfg, os.path.join(a.out, "model.pt"))
    with open(os.path.join(a.out, "metrics.json"), "w", encoding="utf-8") as f:
        json.dump(m, f, ensure_ascii=False, indent=2)
    print("saved to", a.out)


if __name__ == "__main__":
    main()

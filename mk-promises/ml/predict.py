"""Run a trained model over a committee protocol for one promise.

  python predict.py --checkpoint run1/model.pt --protocol protocol.txt \
      --promise "להגדיל את תקציב הביטחון" [--mks mks.json] [--min-conf 0.6]

Writes JSON lines to stdout: speaker, matched MK, class probabilities, suggested label.
These are SUGGESTIONS for a human to review. Nothing here sets a promise status, and
the model has no source of its own: open the protocol before relying on a line.
Only support/contradict lines above --min-conf are printed (context is the majority class).

--mks is an optional JSON list of MK names (e.g. the names exported from the app) used
to match speakers.
"""
import argparse
import json
import sys

import torch

from model import LABELS, load_checkpoint
from protocol_parser import chunk_text, match_mk, parse_protocol


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--checkpoint", required=True)
    ap.add_argument("--protocol", required=True, help="plain-text protocol")
    ap.add_argument("--promise", required=True)
    ap.add_argument("--mks")
    ap.add_argument("--min-conf", type=float, default=0.6)
    ap.add_argument("--source-url", default="", help="URL of the protocol, copied into each output line")
    a = ap.parse_args()

    model, coll, cfg = load_checkpoint(a.checkpoint)
    if cfg.get("synthetic"):
        print("WARNING: this checkpoint was trained on synthetic data only.", file=sys.stderr)
    names = json.load(open(a.mks, encoding="utf-8")) if a.mks else []
    utts = [u for u in parse_protocol(open(a.protocol, encoding="utf-8").read())]
    items = [(u, c) for u in utts for c in chunk_text(u.text)]
    for i in range(0, len(items), 32):
        part = items[i:i + 32]
        batch = coll([{"promise": a.promise, "utterance": c} for _, c in part])
        batch.pop("y")
        with torch.no_grad():
            probs = torch.softmax(model(batch), -1)
        for (u, c), p in zip(part, probs.tolist()):
            k = max(range(len(LABELS)), key=lambda j: p[j])
            if LABELS[k] == "context" or p[k] < a.min_conf:
                continue
            print(json.dumps({
                "speaker": u.speaker, "mk": match_mk(u.speaker, names) if names else None,
                "is_chair": u.is_chair, "suggested": LABELS[k], "probs": dict(zip(LABELS, [round(x, 3) for x in p])),
                "text": c, "url": a.source_url,
            }, ensure_ascii=False))


if __name__ == "__main__":
    main()

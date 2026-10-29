"""Export a trained HASHED checkpoint (LSTM + DNN) to one JSON file the app can load and run on the phone.

  python export_js.py --checkpoint run1/model.pt --out stance-model.json [--fp32]

The app (promise page -> import articles) loads this file and scores article titles against promises.
Only encoder=hashed can run in the app: a pretrained transformer ("hf") is far too big for the WebView.
Tip: train with a smaller --buckets (e.g. 16384) to keep the file small; the embedding table dominates its size.
The embedding table is stored as float16 unless --fp32 is given; everything else is float32.
"""
import argparse
import base64
import json

import numpy as np
import torch


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--checkpoint", required=True)
    ap.add_argument("--out", default="stance-model.json")
    ap.add_argument("--fp32", action="store_true")
    a = ap.parse_args()
    ck = torch.load(a.checkpoint, map_location="cpu")
    cfg = ck["cfg"]
    if cfg["encoder"] != "hashed":
        raise SystemExit("only hashed checkpoints can run inside the app (encoder=%s)" % cfg["encoder"])
    tensors = {}
    for k, v in ck["state"].items():
        arr = v.float().numpy()
        half = k == "enc.emb.weight" and not a.fp32
        tensors[k] = {"shape": list(arr.shape), "dtype": "f16" if half else "f32",
                      "data": base64.b64encode((arr.astype(np.float16) if half else arr.astype(np.float32)).tobytes()).decode()}
    json.dump({"format": "mk-promises-stance-1", "cfg": {k: cfg[k] for k in ("buckets", "dim", "lstm_hidden", "lstm_layers", "dnn")},
               "synthetic": bool(cfg.get("synthetic")), "labels": ["support", "contradict", "context"], "tensors": tensors},
              open(a.out, "w"))
    print("wrote", a.out, "synthetic" if cfg.get("synthetic") else "")


if __name__ == "__main__":
    main()

"""LLM encoder -> BiLSTM -> DNN head, classifying an utterance against a promise.

Input is a pair (promise, utterance from a committee protocol).
Output is one of: support / contradict / context.

    pair --[encoder]--> token vectors --[BiLSTM]--> sequence --[attention + max pooling]--> [DNN] --> 3 classes

Two interchangeable encoders:
  hf      A pretrained transformer ("the LLM"), e.g. a Hebrew BERT. Frozen by default.
          Weights come from Hugging Face, so it needs network access to that host.
  hashed  No pretrained model. Hashed word, Hebrew-prefix and char-3-gram embeddings
          that are trained from scratch. For offline use, tests and baselines.
          This one is NOT an LLM.
"""
import re
import zlib
from contextlib import nullcontext
from functools import lru_cache

import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.nn.utils.rnn import pack_padded_sequence, pad_packed_sequence

LABELS = ["support", "contradict", "context"]
_WORD = re.compile(r"[א-תA-Za-z0-9]+")
_PREFIX = "ובכלמהש"


def tokenize(s):
    return _WORD.findall(s.lower())


@lru_cache(maxsize=200_000)
def token_feats(tok, buckets, max_k):
    """Hash ids (1..buckets-1) for a token: the word, the word without a Hebrew prefix, char 3-grams."""
    feats = ["w:" + tok]
    for k in (1, 2):
        if len(tok) - k >= 2 and all(c in _PREFIX for c in tok[:k]):
            feats.append("p:" + tok[k:])
    w = f"<{tok}>"
    feats += ["c:" + w[i:i + 3] for i in range(len(w) - 2)]
    return tuple(zlib.crc32(f.encode()) % (buckets - 1) + 1 for f in feats[:max_k])


# ---------------------------------------------------------------- hashed (no pretrained model)
class HashedCollator:
    def __init__(self, buckets=2 ** 16, max_k=14, max_promise=40, max_utt=160):
        self.buckets, self.max_k, self.mp, self.mu = buckets, max_k, max_promise, max_utt

    def __call__(self, rows):
        seqs, segs = [], []
        for r in rows:
            p, u = tokenize(r["promise"])[: self.mp], tokenize(r["utterance"])[: self.mu]
            seqs.append(p + ["[sep]"] + u)
            segs.append([0] * (len(p) + 1) + [1] * len(u))
        B, T = len(rows), max(len(s) for s in seqs)
        ids = torch.zeros(B, T, self.max_k, dtype=torch.long)
        seg = torch.zeros(B, T, dtype=torch.long)
        mask = torch.zeros(B, T, dtype=torch.bool)
        for i, (s, g) in enumerate(zip(seqs, segs)):
            mask[i, : len(s)] = True
            seg[i, : len(g)] = torch.tensor(g)
            for j, t in enumerate(s):
                f = token_feats(t, self.buckets, self.max_k)
                ids[i, j, : len(f)] = torch.tensor(f)
        return {"ids": ids, "seg": seg, "attention_mask": mask, "y": _labels(rows)}


class HashedEncoder(nn.Module):
    kind = "hashed"

    def __init__(self, buckets=2 ** 16, dim=128):
        super().__init__()
        self.dim = dim
        self.emb = nn.Embedding(buckets, dim, padding_idx=0)
        self.seg = nn.Embedding(2, dim)

    def forward(self, b):
        e = self.emb(b["ids"])                                  # B,T,K,D
        k = (b["ids"] > 0).unsqueeze(-1).to(e.dtype)
        x = (e * k).sum(2) / k.sum(2).clamp(min=1)              # mean over n-gram features
        return x + self.seg(b["seg"])


# ---------------------------------------------------------------- hf (pretrained LLM)
class HFCollator:
    def __init__(self, tokenizer, max_len=256):
        self.tok, self.max_len = tokenizer, max_len

    def __call__(self, rows):
        enc = self.tok([r["promise"] for r in rows], [r["utterance"] for r in rows],
                       truncation=True, max_length=self.max_len, padding=True, return_tensors="pt")
        b = dict(enc)
        b["y"] = _labels(rows)
        return b


class HFEncoder(nn.Module):
    kind = "hf"

    def __init__(self, name_or_path, freeze=True, local_files_only=False):
        super().__init__()
        from transformers import AutoModel
        self.m = AutoModel.from_pretrained(name_or_path, local_files_only=local_files_only)
        self.dim = self.m.config.hidden_size
        self.freeze = freeze
        if freeze:
            for p in self.m.parameters():
                p.requires_grad = False

    def train(self, mode=True):
        super().train(mode)
        if self.freeze:
            self.m.eval()
        return self

    def forward(self, b):
        kw = {k: b[k] for k in ("input_ids", "attention_mask", "token_type_ids") if k in b}
        with torch.no_grad() if self.freeze else nullcontext():
            return self.m(**kw).last_hidden_state


# ---------------------------------------------------------------- the network
class StanceNet(nn.Module):
    def __init__(self, encoder, lstm_hidden=128, lstm_layers=2, dnn=(256, 128), n_classes=3, dropout=0.3):
        super().__init__()
        self.enc = encoder
        self.drop = nn.Dropout(dropout)
        self.lstm = nn.LSTM(encoder.dim, lstm_hidden, num_layers=lstm_layers, batch_first=True,
                            bidirectional=True, dropout=dropout if lstm_layers > 1 else 0.0)
        h = lstm_hidden * 2
        self.att = nn.Linear(h, 1)
        layers, i = [], h * 2
        for o in dnn:
            layers += [nn.Linear(i, o), nn.GELU(), nn.LayerNorm(o), nn.Dropout(dropout)]
            i = o
        layers.append(nn.Linear(i, n_classes))
        self.head = nn.Sequential(*layers)

    def forward(self, b):
        x = self.drop(self.enc(b))                              # B,T,D
        mask = b["attention_mask"].bool()
        lengths = mask.sum(1).clamp(min=1).cpu()
        packed = pack_padded_sequence(x, lengths, batch_first=True, enforce_sorted=False)
        out, _ = self.lstm(packed)
        out, _ = pad_packed_sequence(out, batch_first=True, total_length=x.size(1))
        a = self.att(out).squeeze(-1).masked_fill(~mask, -1e9)
        att = (F.softmax(a, 1).unsqueeze(-1) * out).sum(1)
        mx = out.masked_fill(~mask.unsqueeze(-1), -1e9).max(1).values
        return self.head(torch.cat([att, mx], -1))


def _labels(rows):
    return torch.tensor([LABELS.index(r["label"]) if "label" in r else -1 for r in rows])


def build(cfg):
    """Create (model, collator) from a config dict. The config is stored in checkpoints."""
    if cfg["encoder"] == "hashed":
        enc = HashedEncoder(cfg["buckets"], cfg["dim"])
        coll = HashedCollator(cfg["buckets"])
    else:
        from transformers import AutoTokenizer
        enc = HFEncoder(cfg["hf_model"], cfg["freeze"], cfg.get("local_only", False))
        tok = AutoTokenizer.from_pretrained(cfg["hf_model"], local_files_only=cfg.get("local_only", False))
        coll = HFCollator(tok, cfg.get("max_len", 256))
    net = StanceNet(enc, cfg["lstm_hidden"], cfg["lstm_layers"], tuple(cfg["dnn"]), len(LABELS), cfg["dropout"])
    return net, coll


def save_checkpoint(model, cfg, path):
    skip = "enc.m." if cfg["encoder"] == "hf" and cfg["freeze"] else None   # frozen LLM is reloaded from its source
    state = {k: v for k, v in model.state_dict().items() if not (skip and k.startswith(skip))}
    torch.save({"cfg": cfg, "state": state}, path)


def load_checkpoint(path, device="cpu"):
    ck = torch.load(path, map_location=device)
    model, coll = build(ck["cfg"])
    missing, unexpected = model.load_state_dict(ck["state"], strict=False)
    bad = [k for k in missing if not k.startswith("enc.m.")]
    if bad or unexpected:
        raise RuntimeError(f"checkpoint mismatch: missing={bad} unexpected={list(unexpected)}")
    return model.to(device).eval(), coll, ck["cfg"]

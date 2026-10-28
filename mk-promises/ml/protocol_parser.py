"""Split a Knesset committee protocol (plain text) into per-speaker utterances.

Heuristic parser. Assumes the common layout in which a speaker header sits on its own
line and ends with a colon, and the speech follows until the next header:

    היו"ר אבי כהן:
    אני פותח את הישיבה...
    דנה לוי (מפלגה א'):
    תודה אדוני היושב ראש...

Markup of the form << ... >> is dropped. This layout was NOT checked against real
protocol files (the Knesset site was not reachable when this was written). Check the
output on a few real protocols before trusting it. Protocols are published as .doc/.docx
files; convert them to text first (e.g. python-docx, or LibreOffice for .doc).
"""
import re
from dataclasses import dataclass

_TAG = re.compile(r"<<[^<>]*>>")
_QUOTES = str.maketrans({"״": '"', "”": '"', "“": '"', "׳": "'", "’": "'", "‘": "'"})
_PAREN = re.compile(r"\([^)]*\)")
_HEADER = re.compile(r"^([^:\n]{2,80}?)\s*:$")
_TITLES = sorted([
    'היו"ר', 'יו"ר', 'חה"כ', 'ח"כ', 'חבר הכנסת', 'חברת הכנסת', 'סגנית השר', 'סגן השר',
    'השרה', 'השר', 'מר', "גב'", 'גברת', 'ד"ר', "פרופ'", 'עו"ד', 'מנכ"ל', 'מנכ"לית', 'רו"ח',
], key=len, reverse=True)
_NOT_SPEAKERS = {"סדר היום", "נכחו", "חברי הוועדה", "חברי הכנסת", "מוזמנים", "נוכחים",
                 "רישום פרלמנטרי", "רשמת פרלמנטרית", "מנהל הוועדה", "הצעת החלטה"}


@dataclass
class Utterance:
    index: int
    speaker_raw: str
    speaker: str      # normalised name, titles and party removed
    is_chair: bool
    text: str


def _norm(s):
    return " ".join(s.translate(_QUOTES).split())


def clean_speaker(raw):
    s = _norm(_PAREN.sub(" ", _TAG.sub("", raw)))
    changed = True
    while changed:
        changed = False
        for t in _TITLES:
            if s.startswith(t + " ") or s == t:
                s = s[len(t):].strip()
                changed = True
    return s.strip(" -–,")


def _is_header(line):
    m = _HEADER.match(line)
    if not m:
        return None
    name = m.group(1).strip()
    if _norm(name) in _NOT_SPEAKERS or name[0].isdigit() or len(name.split()) > 9:
        return None
    if any(c in name for c in ".?!;"):
        return None
    return name


def parse_protocol(text):
    out, cur, buf = [], None, []

    def flush():
        if cur is None:
            return
        body = " ".join(" ".join(buf).split())
        if body:
            raw = _norm(cur)
            out.append(Utterance(len(out), raw, clean_speaker(raw), "יו\"ר" in raw, body))

    for line in text.splitlines():
        line = _TAG.sub("", line).strip()
        if not line:
            continue
        name = _is_header(line)
        if name:
            flush()
            cur, buf = name, []
        elif cur is not None:
            buf.append(line)
    flush()
    return out


def match_mk(speaker, names):
    """Match a cleaned speaker name to one of `names` (iterable of full names).

    Returns the matching name, or None when there is no match or the match is ambiguous.
    """
    s = _norm(speaker)
    toks = set(s.split())
    exact = [n for n in names if _norm(n) == s]
    if len(exact) == 1:
        return exact[0]
    hits = []
    for n in names:
        nt = set(_norm(n).split())
        if len(nt) >= 2 and len(toks) >= 2 and (nt <= toks or toks <= nt):
            hits.append(n)
    return hits[0] if len(hits) == 1 else None


def chunk_text(text, max_words=80):
    """Split long speech at sentence ends into pieces of at most ~max_words words."""
    sents = [x.strip() for x in re.split(r"(?<=[.?!])\s+", text) if x.strip()]
    chunks, cur, n = [], [], 0
    for s in sents:
        w = len(s.split())
        if cur and n + w > max_words:
            chunks.append(" ".join(cur))
            cur, n = [], 0
        cur.append(s)
        n += w
    if cur:
        chunks.append(" ".join(cur))
    return chunks

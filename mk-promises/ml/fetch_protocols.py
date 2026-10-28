"""Download committee protocols from the Knesset and convert them to plain text.

RUN THIS ON YOUR OWN COMPUTER, from an Israeli connection. The Knesset site is not
reachable from outside Israel, so this cannot run in the cloud workspace.

  python fetch_protocols.py --committee 2211 --from 2026-04-27 --to 2026-10-27 --out protocols

Committee 2211 is the number in the URL
  https://main.knesset.gov.il/APPS/committees/2211/sessions
Standard library only. Output, per session: <date>_<sessionId>.txt, plus manifest.json
with the source URL of every file (feed it to predict.py --source-url).

NOT VERIFIED against the live server. The entity and field names below
(KNS_CommitteeSession, KNS_DocumentCommitteeSession: GroupTypeDesc, FilePath) come from
public documentation. If nothing is found, run with --list-types to see which document
types the server reports, then pass the right word with --doc-type.

Conversion: .docx is read directly. .doc needs `antiword` or LibreOffice (`soffice`) on
PATH; without them the original file is kept and the manifest says "needs conversion".
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

BASE = "https://knesset.gov.il/OdataV4/ParliamentInfo/"
UA = {"User-Agent": "mk-promises-research/1.0", "Accept": "application/json"}


def http(url, binary=False, tries=3):
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
                data = r.read()
            return data if binary else json.loads(data.decode("utf-8"))
        except Exception:
            if i == tries - 1:
                raise
            time.sleep(2 * (i + 1))


def odata(base, entity, flt, select, max_rows=20000):
    # No $top: it would cap the whole result; the server pages 100 rows at a time and gives @odata.nextLink.
    q = urllib.parse.urlencode({"$filter": flt, "$select": select}, quote_via=urllib.parse.quote)
    url, rows = f"{base}{entity}?{q}", []
    while url and len(rows) < max_rows:
        j = http(url)
        rows += j.get("value", [])
        url = j.get("@odata.nextLink")
    return rows


def docx_text(path):
    w = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
    with zipfile.ZipFile(path) as z:
        root = ET.fromstring(z.read("word/document.xml"))
    return "\n".join("".join(t.text or "" for t in p.iter(w + "t")) for p in root.iter(w + "p"))


def plausible(text):
    """Converted .doc output must look like a Hebrew protocol, not binary noise."""
    return len(re.findall("[א-ת]", text or "")) >= 50


def convert(path):
    """Return (text or None, note)."""
    with open(path, "rb") as f:
        magic = f.read(8)
    if magic[:2] == b"PK":
        return docx_text(path), "docx"
    if magic[:4] == b"\xd0\xcf\x11\xe0":
        if shutil.which("antiword"):
            out = subprocess.run(["antiword", "-m", "UTF-8.txt", path], capture_output=True)
            text = out.stdout.decode("utf-8", "replace")
            if out.returncode == 0 and plausible(text):
                return text, "doc via antiword"
        soffice = shutil.which("soffice") or shutil.which("libreoffice")
        if soffice:
            with tempfile.TemporaryDirectory() as d:
                subprocess.run([soffice, "--headless", "--convert-to", "txt:Text", "--outdir", d, path], capture_output=True)
                for n in os.listdir(d):
                    if n.endswith(".txt"):
                        text = open(os.path.join(d, n), encoding="utf-8", errors="replace").read()
                        if plausible(text):
                            return text, "doc via soffice"
        return None, "needs conversion (.doc: install antiword or LibreOffice)"
    if magic[:4] == b"%PDF":
        return None, "needs conversion (PDF)"
    return None, "unknown file type"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--committee", type=int, help="one committee id; omit to collect all committees of --knesset")
    ap.add_argument("--knesset", type=int, default=25, help="Knesset number, used when --committee is omitted")
    ap.add_argument("--from", dest="d_from", help="YYYY-MM-DD (optional)")
    ap.add_argument("--to", dest="d_to", help="YYYY-MM-DD (optional)")
    ap.add_argument("--out", default="protocols")
    ap.add_argument("--base", default=BASE)
    ap.add_argument("--doc-type", default="פרוטוקול", help="substring of GroupTypeDesc to keep")
    ap.add_argument("--list-types", action="store_true", help="only print the document types found")
    ap.add_argument("--delay", type=float, default=0.5, help="seconds between downloads")
    a = ap.parse_args()

    parts = [f"CommitteeID eq {a.committee}" if a.committee else f"KnessetNum eq {a.knesset}"]
    if a.d_from:
        parts.append(f"StartDate ge {a.d_from}T00:00:00Z")
    if a.d_to:
        parts.append(f"StartDate le {a.d_to}T23:59:59Z")
    sess = odata(a.base, "KNS_CommitteeSession", " and ".join(parts), "Id,StartDate", max_rows=10**7)   # OData v4: the key is Id
    print(f"{len(sess)} sessions in range")
    by_id = {s["Id"]: s["StartDate"][:10] for s in sess}
    docs = []
    ids = list(by_id)
    for i in range(0, len(ids), 20):
        flt = " or ".join(f"CommitteeSessionID eq {x}" for x in ids[i:i + 20])   # foreign key in the document table
        docs += odata(a.base, "KNS_DocumentCommitteeSession", flt, "CommitteeSessionID,GroupTypeDesc,ApplicationDesc,FilePath")
    types = sorted({d.get("GroupTypeDesc") or "" for d in docs})
    if a.list_types:
        print("document types:", json.dumps(types, ensure_ascii=False))
        return
    docs = [d for d in docs if a.doc_type in (d.get("GroupTypeDesc") or "") and d.get("FilePath")]
    print(f"{len(docs)} protocol files (types seen: {types})")
    os.makedirs(a.out, exist_ok=True)
    manifest = []
    for d in docs:
        sid, date, url = d["CommitteeSessionID"], by_id[d["CommitteeSessionID"]], d["FilePath"]
        stem = os.path.join(a.out, f"{date}_{sid}")
        if os.path.exists(stem + ".txt"):
            manifest.append({"session": sid, "date": date, "url": url, "text": stem + ".txt", "note": "already downloaded"})
            continue
        raw = stem + ".orig"
        try:
            with open(raw, "wb") as f:
                f.write(http(url, binary=True))
            text, note = convert(raw)
        except Exception as e:
            manifest.append({"session": sid, "date": date, "url": url, "text": None, "note": f"failed: {e}"})
            continue
        if text:
            open(stem + ".txt", "w", encoding="utf-8").write(text)
            os.remove(raw)
        manifest.append({"session": sid, "date": date, "url": url, "text": stem + ".txt" if text else None,
                         "original": None if text else raw, "note": note})
        print(date, sid, note)
        time.sleep(a.delay)
    json.dump(manifest, open(os.path.join(a.out, "manifest.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print("done; manifest at", os.path.join(a.out, "manifest.json"))


if __name__ == "__main__":
    main()

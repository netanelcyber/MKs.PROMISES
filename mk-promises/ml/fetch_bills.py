"""Download every row of KNS_Bill with a $skip loop, 100 rows per page.

RUN THIS ON YOUR OWN COMPUTER, from an Israeli connection. The Knesset OData host
refuses connections from outside Israel, so it cannot run in the cloud workspace.

  skip = 0
  while the page is not empty:
      GET .../KNS_Bill?$skip=<skip>&$top=100&$orderby=Id
      skip += 100

  python fetch_bills.py --out bills.jsonl
  python fetch_bills.py --out bills.jsonl --knesset 25        # only the 25th Knesset
  python fetch_bills.py --out bills.jsonl --resume            # continue after an interruption

Output: JSONL, one bill per line, all fields the server returns. Standard library only.

NOT VERIFIED against the live server. If it rejects $orderby the script retries once
without it and warns, because without a stable order $skip paging can repeat or miss rows;
rows are de-duplicated by the entity key (`Id` in OData v4; `BillID` is accepted too) and the final count is printed so you can compare it with
the total the server reports.
"""
import argparse
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "https://knesset.gov.il/OdataV4/ParliamentInfo/"
UA = {"User-Agent": "mk-promises-research/1.0", "Accept": "application/json"}


def get(url, tries=3):
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
                return json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            if e.code < 500 and e.code != 429:      # a 4xx will not get better by retrying
                raise
            if i == tries - 1:
                raise
        except Exception:
            if i == tries - 1:
                raise
        time.sleep(2 * (i + 1))


def page_url(base, skip, orderby, flt):
    q = {"$skip": str(skip), "$top": "100"}
    if orderby:
        q["$orderby"] = orderby
    if flt:
        q["$filter"] = flt
    return f"{base}KNS_Bill?" + urllib.parse.urlencode(q, quote_via=urllib.parse.quote, safe="$")


def bill_key(row):
    """OData v4 names the key Id; fall back to BillID so a v3-style dump still works."""
    return row["Id"] if "Id" in row else row["BillID"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="bills.jsonl")
    ap.add_argument("--base", default=BASE)
    ap.add_argument("--knesset", type=int, help="only this Knesset number")
    ap.add_argument("--orderby", default="Id", help="OData v4 entity key (older v3 services used BillID)")
    ap.add_argument("--delay", type=float, default=0.3, help="seconds between pages")
    ap.add_argument("--max-pages", type=int, default=5000, help="safety stop")
    ap.add_argument("--resume", action="store_true", help="continue from the rows already in --out")
    a = ap.parse_args()

    flt = f"KnessetNum eq {a.knesset}" if a.knesset else None
    seen, skip = set(), 0
    if a.resume and os.path.exists(a.out):
        with open(a.out, encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    seen.add(bill_key(json.loads(line)))
        skip = (len(seen) // 100) * 100          # re-read the last partial page; de-dup handles the overlap
        print(f"resuming: {len(seen)} rows already saved, starting at $skip={skip}")
    orderby = a.orderby
    mode = "a" if a.resume else "w"
    pages = 0
    with open(a.out, mode, encoding="utf-8") as out:
        while pages < a.max_pages:
            try:
                j = get(page_url(a.base, skip, orderby, flt))
            except urllib.error.HTTPError as e:
                if orderby and e.code in (400, 501) and skip == 0:
                    print(f"server rejected $orderby ({e.code}); retrying without it. Paging may repeat or miss rows.")
                    orderby = None
                    continue
                raise
            rows = j.get("value", [])
            if not rows:
                break
            new = 0
            for r in rows:
                key = bill_key(r)
                if key in seen:
                    continue
                seen.add(key)
                out.write(json.dumps(r, ensure_ascii=False) + "\n")
                new += 1
            out.flush()
            pages += 1
            print(f"$skip={skip:6d}  rows={len(rows):3d}  new={new:3d}  total={len(seen)}")
            skip += 100
            time.sleep(a.delay)
    print(f"done: {len(seen)} bills in {a.out}" + ("  (stopped at --max-pages)" if pages >= a.max_pages else ""))


if __name__ == "__main__":
    main()

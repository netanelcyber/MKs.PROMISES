"""Collect plenum votes of members of the Knesset. RUN ON YOUR OWN COMPUTER (Israeli connection).

  python fetch_votes.py --mk 123 --from 2023-01-01 --to 2026-10-01 --out votes.jsonl
  python fetch_votes.py --list-fields      # print the real field names the server returns

--mk is the member's KNS_Person Id (same as apiPersonId in the app). Several --mk allowed.
Schema (checked against a public mirror of the data, not the live server):
  KNS_PlenumVoteResult: one row per member per vote (MkId, VoteID, VoteDate, ResultDesc)
  KNS_PlenumVote: the vote itself, joined by Id = VoteID. No $select is used on it,
  so an unexpected field name cannot cause an HTTP 400.
Standard library only. Output: one JSON object per line.
"""
import argparse
import json
import time
import urllib.parse
import urllib.request

BASE = "https://knesset.gov.il/OdataV4/ParliamentInfo/"
UA = {"User-Agent": "mk-promises-research/1.0", "Accept": "application/json"}


def get(url, tries=3):
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception:
            if i == tries - 1:
                raise
            time.sleep(2 * (i + 1))


def rows(base, entity, flt=None, select=None, delay=0.2):
    """Page with $skip by hand: @odata.nextLink loses the $ of its options."""
    skip, out = 0, []
    while True:
        q = {"$skip": skip, "$orderby": "Id"}
        if flt:
            q["$filter"] = flt
        if select:
            q["$select"] = select
        j = get(f"{base}{entity}?" + urllib.parse.urlencode(q, quote_via=urllib.parse.quote))
        v = j.get("value", [])
        out += v
        if len(v) < 100:
            return out
        skip += 100
        time.sleep(delay)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mk", type=int, action="append", default=[])
    ap.add_argument("--from", dest="d_from")
    ap.add_argument("--to", dest="d_to")
    ap.add_argument("--out", default="votes.jsonl")
    ap.add_argument("--base", default=BASE)
    ap.add_argument("--list-fields", action="store_true")
    a = ap.parse_args()
    if a.list_fields:
        for e in ("KNS_PlenumVoteResult", "KNS_PlenumVote"):
            v = get(f"{a.base}{e}?$top=1").get("value", [])
            print(e, sorted(v[0]) if v else "(no rows)")
        return
    if not a.mk:
        ap.error("give at least one --mk")
    with open(a.out, "w", encoding="utf-8") as f:
        for mk in a.mk:
            parts = [f"MkId eq {mk}"]
            if a.d_from:
                parts.append(f"VoteDate ge {a.d_from}T00:00:00Z")
            if a.d_to:
                parts.append(f"VoteDate le {a.d_to}T23:59:59Z")
            res = rows(a.base, "KNS_PlenumVoteResult", " and ".join(parts))
            ids = sorted({r["VoteID"] for r in res if r.get("VoteID") is not None})
            votes = {}
            for i in range(0, len(ids), 10):   # the server limits filters to ~100 nodes
                flt = " or ".join(f"Id eq {x}" for x in ids[i:i + 10])
                for v in rows(a.base, "KNS_PlenumVote", flt):
                    votes[v["Id"]] = v
            for r in res:
                f.write(json.dumps({"mk": mk, "result": r, "vote": votes.get(r.get("VoteID"))}, ensure_ascii=False) + "\n")
            print(f"MK {mk}: {len(res)} votes")
    print("wrote", a.out)


if __name__ == "__main__":
    main()

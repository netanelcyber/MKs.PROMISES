"""Collect article METADATA (title, date, url) about members from a news site. RUN ON YOUR OWN COMPUTER.

  python collect_articles.py --query "שם חבר הכנסת" --out articles.jsonl
  python collect_articles.py --backup mk-promises-backup.json --out articles.jsonl

Defaults to Makor Rishon (makorrishon.co.il). Only titles, dates and links are saved, never the article
text (copyright; paywall). robots.txt is honoured, with a delay between requests.
NOT VERIFIED: the site's search URL (/?s=QUERY) was never reachable from the build environment, so it is
a guess. If the site changed or blocks scripts, the run reports 0 results and does not retry harder; use
--search-url to point at another pattern containing {q}. Check the site's terms of use before running.
"""
import argparse
import json
import re
import time
import urllib.parse
import urllib.request
import urllib.robotparser
from html.parser import HTMLParser

SITE = "https://www.makorrishon.co.il"
UA = "mk-promises-research/1.0 (metadata only)"


class Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.items, self._href, self._text, self.ld, self._in_ld = [], None, [], [], False

    def handle_starttag(self, tag, attrs):
        d = dict(attrs)
        if tag == "a" and d.get("href"):
            self._href, self._text = d["href"], []
        if tag == "script" and d.get("type") == "application/ld+json":
            self._in_ld = True

    def handle_data(self, data):
        if self._href:
            self._text.append(data)
        if self._in_ld:
            self.ld.append(data)

    def handle_endtag(self, tag):
        if tag == "a" and self._href:
            t = re.sub(r"\s+", " ", "".join(self._text)).strip()
            if t:
                self.items.append((self._href, t))
            self._href = None
        if tag == "script":
            self._in_ld = False


def parse(html, base):
    p = Links()
    p.feed(html)
    dates = {}
    for blob in p.ld:
        try:
            j = json.loads(blob)
        except ValueError:
            continue
        for n in (j if isinstance(j, list) else j.get("@graph", [j]) if isinstance(j, dict) else []):
            if isinstance(n, dict) and n.get("url") and n.get("datePublished"):
                dates[n["url"]] = n["datePublished"]
    out, seen = [], set()
    for href, title in p.items:
        url = urllib.parse.urljoin(base, href).split("#")[0]
        host = urllib.parse.urlparse(url).netloc
        if host != urllib.parse.urlparse(base).netloc or url in seen or len(title) < 12 or url.rstrip("/") == base:
            continue
        if not re.search(r"/[^/]+/[^/]+", urllib.parse.urlparse(url).path):
            continue   # skip bare section links
        seen.add(url)
        out.append({"title": title, "url": url, "date": dates.get(url)})
    return out


def queries_from_backup(path):
    s = json.load(open(path, encoding="utf-8"))
    return [m["name"] for m in s.get("mks", []) if m.get("name")]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--query", action="append", default=[])
    ap.add_argument("--backup", help="app backup JSON; searches every member name in it")
    ap.add_argument("--site", default=SITE)
    ap.add_argument("--search-url", default=None, help="pattern with {q}; default <site>/?s={q}")
    ap.add_argument("--out", default="articles.jsonl")
    ap.add_argument("--delay", type=float, default=3.0)
    a = ap.parse_args()
    qs = a.query + (queries_from_backup(a.backup) if a.backup else [])
    if not qs:
        ap.error("give --query or --backup")
    pattern = a.search_url or a.site.rstrip("/") + "/?s={q}"
    rp = urllib.robotparser.RobotFileParser(a.site.rstrip("/") + "/robots.txt")
    try:
        rp.read()
    except Exception:
        print("could not read robots.txt; stopping rather than guessing")
        return
    total = 0
    with open(a.out, "w", encoding="utf-8") as f:
        for q in qs:
            url = pattern.format(q=urllib.parse.quote(q))
            if not rp.can_fetch(UA, url):
                print(f"{q}: disallowed by robots.txt, skipped")
                continue
            try:
                req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "he"})
                html = urllib.request.urlopen(req, timeout=30).read().decode("utf-8", "replace")
            except Exception as e:
                print(f"{q}: failed ({e})")
                continue
            items = parse(html, a.site)
            for it in items:
                f.write(json.dumps({"query": q, **it}, ensure_ascii=False) + "\n")
            total += len(items)
            print(f"{q}: {len(items)} links")
            time.sleep(a.delay)
    print("wrote", a.out, total, "records")


if __name__ == "__main__":
    main()

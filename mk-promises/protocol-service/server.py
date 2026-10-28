#!/usr/bin/env python3
"""Explicitly configured Knesset protocol reader using W3C Remote WebDriver."""

from __future__ import annotations

import hmac
import io
import json
import logging
import os
import re
import signal
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from dataclasses import dataclass, field
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MAX_BODY_BYTES = 32 * 1024
MAX_DOWNLOAD_BYTES = 12 * 1024 * 1024
MAX_RESPONSE_BYTES = 512 * 1024
MAX_DRIVER_BYTES = 1024 * 1024
MAX_TEXT_CHARS = 100_000
MAX_URL_CHARS = 4096
MAX_DOCUMENTS = 50
WORK_SECONDS = 65
BLOCKED_STATUSES = {247, 401, 403, 407, 429, 473, 481, 491}
FORMATS = {"pdf", "docx", "doc"}


class ServiceError(Exception):
    """A bounded public error, never an unfiltered upstream exception."""

    def __init__(self, status, code, message):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


def strict_json(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate key")
            result[key] = value
        return result

    def invalid(_value):
        raise ValueError("non-finite number")

    return json.loads(raw, object_pairs_hook=unique, parse_constant=invalid)


def official_url(value):
    if not isinstance(value, str) or not value or len(value) > MAX_URL_CHARS:
        raise ServiceError(400, "invalid_url", "נדרש קישור HTTPS רשמי של הכנסת")
    if any(ord(c) <= 32 or ord(c) >= 127 for c in value) or "\\" in value:
        raise ServiceError(400, "invalid_url", "יש להשתמש בקישור תקין ומקודד של הכנסת")
    try:
        u = urllib.parse.urlsplit(value)
        host = u.hostname or ""
        valid_host = host == "knesset.gov.il" or (
            host.endswith(".knesset.gov.il")
            and all(re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", x)
                    for x in host.split("."))
        )
        if (u.scheme != "https" or not valid_host or u.port not in {None, 443}
                or u.username is not None or u.password is not None):
            raise ValueError()
    except ValueError:
        raise ServiceError(400, "invalid_url", "מותר לקרוא רק כתובות HTTPS של knesset.gov.il ותתי הדומיין שלו") from None
    return urllib.parse.urlunsplit(("https", host, u.path or "/", u.query, ""))


def parse_origins(value):
    origins = set()
    for origin in value.split(","):
        origin = origin.strip()
        if not origin:
            continue
        if origin != "null":
            u = urllib.parse.urlsplit(origin)
            if (u.scheme not in {"http", "https"} or not u.hostname or u.path
                    or u.query or u.fragment or u.username is not None or u.password is not None
                    or "*" in origin or any(c.isspace() for c in origin)):
                raise ValueError("PROTOCOL_ALLOWED_ORIGINS requires exact origins")
            u.port
        origins.add(origin)
    return frozenset(origins)


@dataclass(frozen=True)
class Config:
    selenium_url: str = field(repr=False)
    host: str = "127.0.0.1"
    port: int = 8788
    allowed_origins: frozenset[str] = frozenset()
    api_token: str = field(default="", repr=False)

    @classmethod
    def from_env(cls):
        grid = os.environ.get("SELENIUM_URL", "").strip().rstrip("/")
        u = urllib.parse.urlsplit(grid)
        if (u.scheme not in {"http", "https"} or not u.hostname or u.query or u.fragment
                or u.username is not None or u.password is not None
                or any(ord(c) <= 32 or ord(c) >= 127 for c in grid)):
            raise ValueError("Set SELENIUM_URL to your private Selenium Grid HTTP(S) endpoint")
        u.port
        host = os.environ.get("PROTOCOL_HOST", "127.0.0.1").strip()
        if not host or any(c.isspace() for c in host):
            raise ValueError("PROTOCOL_HOST must be a host address")
        port = int(os.environ.get("PROTOCOL_PORT", "8788"))
        if not 1 <= port <= 65535:
            raise ValueError("PROTOCOL_PORT must be between 1 and 65535")
        origins = parse_origins(os.environ.get("PROTOCOL_ALLOWED_ORIGINS", ""))
        token = os.environ.get("PROTOCOL_API_TOKEN", "").strip()
        if len(token) > 512 or any(ord(c) < 33 or ord(c) > 126 for c in token):
            raise ValueError("PROTOCOL_API_TOKEN must use visible ASCII, at most 512 characters")
        if (host not in {"127.0.0.1", "localhost"} or "null" in origins) and not token:
            raise ValueError("Set PROTOCOL_API_TOKEN for non-loopback access or a null origin")
        return cls(grid, host, port, origins, token)


def validate_request(raw):
    if len(raw) > MAX_BODY_BYTES:
        raise ServiceError(413, "request_too_large", "גוף הבקשה גדול מדי")
    try:
        value = strict_json(raw.decode("utf-8"))
    except (ValueError, UnicodeError, RecursionError):
        raise ServiceError(400, "invalid_json", "נדרש JSON תקין בקידוד UTF-8") from None
    if not isinstance(value, dict) or set(value) != {"url"}:
        raise ServiceError(400, "invalid_request", "נדרש אובייקט עם שדה url בלבד")
    return official_url(value["url"])


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def remaining(deadline, cap=30):
    seconds = deadline - time.monotonic()
    if seconds <= 0:
        raise ServiceError(504, "read_timeout", "קריאת המקור ארכה זמן רב מדי")
    return min(cap, seconds)


def bounded_read(response, maximum, deadline):
    """Check the total budget between single socket reads, not just inactivity."""
    chunks, size = [], 0
    read = getattr(response, "read1", response.read)
    while size <= maximum:
        timeout = remaining(deadline, 5)
        # urllib's HTTPResponse uses a buffered SocketIO. Adjust its next read to
        # the remaining budget; fake/test responses need no socket adjustment.
        raw = getattr(getattr(response, "fp", None), "raw", None)
        sock = getattr(raw, "_sock", None)
        if sock is not None:
            sock.settimeout(timeout)
        chunk = read(min(64 * 1024, maximum + 1 - size))
        remaining(deadline)
        if not chunk:
            break
        chunks.append(chunk)
        size += len(chunk)
    return b"".join(chunks)


class GridClient:
    """Small W3C HTTP client; production scripts are fixed constants below."""

    def __init__(self, url, *, open_url=None, deadline=None):
        self.url = url.rstrip("/")
        self.open_url = open_url or urllib.request.build_opener(NoRedirect()).open
        self.deadline = deadline if deadline is not None else time.monotonic() + WORK_SECONDS
        self.session_id = None

    def command(self, method, path, payload=None, *, cleanup=False):
        data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(self.url + path, data=data, method=method,
                                        headers={"Content-Type": "application/json", "Accept": "application/json"})
        try:
            with self.open_url(request, timeout=5 if cleanup else remaining(self.deadline, 35)) as response:
                raw = bounded_read(response, MAX_DRIVER_BYTES,
                                   time.monotonic() + 5 if cleanup else self.deadline)
                status = response.status
        except urllib.error.HTTPError as error:
            error.close()
            raise ServiceError(502, "selenium_error", "שרת Selenium לא השלים את פעולת הקריאה") from None
        except (TimeoutError, socket.timeout):
            raise ServiceError(504, "selenium_timeout", "שרת Selenium לא השיב בזמן") from None
        except (urllib.error.URLError, OSError):
            raise ServiceError(502, "selenium_unavailable", "לא ניתן להתחבר לשרת Selenium שהוגדר") from None
        try:
            value = strict_json(raw.decode("utf-8"))
            if status != 200 or len(raw) > MAX_DRIVER_BYTES or not isinstance(value, dict) or "value" not in value:
                raise ValueError()
            result = value["value"]
            if isinstance(result, dict) and "error" in result:
                raise ValueError()
            return result
        except (ValueError, UnicodeError, RecursionError):
            raise ServiceError(502, "invalid_selenium_response", "שרת Selenium החזיר תשובה שאינה תקינה") from None

    def new_session(self):
        result = self.command("POST", "/session", {"capabilities": {"alwaysMatch": {
            "browserName": "chrome", "pageLoadStrategy": "eager",
            "acceptInsecureCerts": False, "unhandledPromptBehavior": "dismiss and notify",
            "timeouts": {"pageLoad": 30000, "script": 10000, "implicit": 0},
            "goog:chromeOptions": {"args": ["--headless=new", "--incognito", "--disable-dev-shm-usage"],
                                   "prefs": {"download_restrictions": 3}},
        }}})
        sid = result.get("sessionId") if isinstance(result, dict) else None
        if not isinstance(sid, str) or not re.fullmatch(r"[A-Za-z0-9._-]{1,200}", sid):
            raise ServiceError(502, "invalid_selenium_session", "לא נוצרה הפעלת דפדפן תקינה")
        self.session_id = sid
        return sid

    def session_command(self, method, suffix, payload=None):
        if self.session_id is None:
            raise ServiceError(502, "no_session", "לא נוצרה הפעלת דפדפן")
        return self.command(method, "/session/" + self.session_id + suffix, payload)

    def close(self):
        if self.session_id is not None:
            sid, self.session_id = self.session_id, None
            self.command("DELETE", "/session/" + sid, cleanup=True)


# No caller input can replace this script. It reads the rendered DOM and actual anchors.
SNAPSHOT_SCRIPT = r"""
const limit = 100000;
const body = document.body;
const main = document.querySelector('main, article, [role="main"]') || body;
const text = main ? main.innerText : '';
const all = Array.from(document.querySelectorAll('a[href]'));
const navigation = performance.getEntriesByType('navigation')[0];
const visible = e => !!(e && e.getClientRects().length && getComputedStyle(e).visibility !== 'hidden');
return {
  finalUrl: location.href, title: document.title,
  text: Array.from(text).slice(0, limit + 1).join(''),
  truncated: Array.from(text).length > limit,
  status: navigation && navigation.responseStatus || 0,
  login: Array.from(document.querySelectorAll('input[type="password"]')).some(visible),
  captcha: Array.from(document.querySelectorAll('iframe[src*="captcha"], .g-recaptcha, .h-captcha, #captcha')).some(visible),
  links: all.slice(0, 500).map(a => ({url: a.href, title: (a.innerText || a.getAttribute('aria-label') || '').slice(0, 300), type: a.type || ''})),
  linksTruncated: all.length > 500
};
"""


def document_kind(url):
    u = urllib.parse.urlsplit(url)
    candidates = [u.path] + [v for _k, v in urllib.parse.parse_qsl(u.query)]
    for value in candidates:
        match = re.search(r"\.(pdf|docx|doc)(?:$|[?#])", urllib.parse.unquote(value), re.I)
        if match:
            return match.group(1).lower()
    return None


def clean_text(value, limit):
    if not isinstance(value, str):
        return ""
    value = value.replace("\r\n", "\n").replace("\r", "\n")
    # Keep extracted wording and whitespace; replace invalid JSON/control characters only.
    value = "".join(c if (ord(c) >= 32 or c in "\n\t") and not 0xD800 <= ord(c) <= 0xDFFF else "\ufffd" for c in value)
    return value[:limit]


def blocked_page(url, title, text):
    head = (title + "\n" + text[:5000]).lower()
    return ("maintenance-page-geo" in url.lower()
            or any(marker in head for marker in (
                "access denied", "temporarily inaccessible from your location", "request rejected",
                "verify you are human", "checking your browser", "גישה חסומה", "הגישה נחסמה",
                "אינך מורשה", "אינך רשאי לגשת", "אימות אנושי")))


def source_blocked():
    return ServiceError(403, "source_blocked", "המקור חסום, דורש התחברות או מציג בדיקת גישה. הקריאה הופסקה")


def protocol_markers(text, *, archive_hint=False):
    head = text[:10000]
    numbered_heading = re.search(r"פרוטוקול\s+(?:מס(?:פר)?[׳'’\"]?\.?\s*)?\d+", head)
    meeting = re.search(r"(?:ישיבת|ישיבה\s+של)\s+(?:ועדת|הוועדה)", head)
    participants = re.search(r"(?:נכחו|נוכחים|חברי\s+הוועדה)\s*[:：]", head)
    agenda = re.search(r"סדר[\s־-]+היום\s*[:：]", head)
    return bool(len(text.strip()) >= 250 and numbered_heading and meeting
                and (archive_hint or (participants and agenda)))


def explicit_metadata(text, is_protocol):
    if not is_protocol:
        return None, None
    head = text[:10000]
    committee = re.search(r"(?m)^\s*(?:שם\s+הוועדה|ועדה)\s*[:：]\s*([^\n]{2,200})$", head)
    date = re.search(r"(?m)^\s*תאריך\s*[:：]\s*([^\n]{2,80})$", head)
    return (committee.group(1).strip() if committee else None,
            date.group(1).strip() if date else None)


def result_for(source, final, title, text, kind, *, truncated=False, documents=None, warnings=None):
    warnings = list(warnings or [])
    text_length = len(text)
    text = clean_text(text, MAX_TEXT_CHARS)
    archive_name = urllib.parse.urlsplit(final).path.lower()
    is_protocol = bool(kind != "page" and "_cs_bg_" not in archive_name
                       and protocol_markers(text, archive_hint="_ptv_" in archive_name))
    committee, date = explicit_metadata(text, is_protocol)
    if not is_protocol and text.strip():
        warnings.append("הטקסט לא זוהה בוודאות כגוף פרוטוקול. יש לבדוק את המקור לפני שימוש כראיה")
    result = {
        "sourceUrl": source, "finalUrl": final, "title": clean_text(title, 300),
        "committee": committee, "date": date, "text": text, "kind": kind,
        "isProtocol": is_protocol, "truncated": bool(truncated or text_length > MAX_TEXT_CHARS),
        "retrievedAt": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "documents": (documents or [])[:MAX_DOCUMENTS],
        "warnings": [clean_text(w, 300) for w in warnings[:8]],
    }
    return bound_response(result)


def json_bytes(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")


def bound_response(result):
    if len(json_bytes(result)) <= MAX_RESPONSE_BYTES:
        return result
    result["truncated"] = True
    result["warnings"] = result["warnings"][:7] + ["התוצאה קוצרה כדי לעמוד במגבלת גודל התשובה"]
    while result["documents"] and len(json_bytes(result)) > MAX_RESPONSE_BYTES:
        result["documents"].pop()
    if len(json_bytes(result)) > MAX_RESPONSE_BYTES:
        text, low, high = result["text"], 0, len(result["text"])
        while low < high:
            mid = (low + high + 1) // 2
            result["text"] = text[:mid]
            if len(json_bytes(result)) <= MAX_RESPONSE_BYTES:
                low = mid
            else:
                high = mid - 1
        result["text"] = text[:low]
    return result


def read_page(source, config, *, grid_factory=GridClient, deadline=None):
    deadline = deadline if deadline is not None else time.monotonic() + WORK_SECONDS
    grid = grid_factory(config.selenium_url, deadline=deadline)
    try:
        grid.new_session()
        grid.session_command("POST", "/url", {"url": source})
        final = grid.session_command("GET", "/url")
        try:
            final = official_url(final)
        except ServiceError:
            raise ServiceError(403, "redirect_denied", "המקור הפנה לכתובת שאינה מותרת לקריאה") from None
        snapshot, previous = None, None
        for attempt in range(8):
            snapshot = grid.session_command("POST", "/execute/sync", {"script": SNAPSHOT_SCRIPT, "args": []})
            if not isinstance(snapshot, dict) or not isinstance(snapshot.get("text"), str):
                raise ServiceError(502, "invalid_page", "לא התקבל טקסט תקין מהדפדפן")
            try:
                final = official_url(snapshot.get("finalUrl"))
            except ServiceError:
                raise ServiceError(403, "redirect_denied", "המקור הפנה לכתובת שאינה מותרת לקריאה") from None
            title = clean_text(snapshot.get("title"), 300)
            text = snapshot["text"]
            if (snapshot.get("status") in BLOCKED_STATUSES or snapshot.get("login")
                    or snapshot.get("captcha") or blocked_page(final, title, text)):
                raise source_blocked()
            if isinstance(snapshot.get("status"), int) and snapshot["status"] >= 400:
                raise ServiceError(502, "source_http_error", "אתר הכנסת החזיר שגיאה בעת טעינת הדף")
            fingerprint = (text, len(snapshot.get("links", [])))
            if (text.strip() and fingerprint == previous) or attempt == 7:
                break
            previous = fingerprint
            time.sleep(min(0.5, remaining(deadline)))
        documents, seen = [], set()
        links = snapshot.get("links", [])
        if not isinstance(links, list):
            raise ServiceError(502, "invalid_page", "רשימת הקישורים שהתקבלה אינה תקינה")
        for link in links[:500]:
            if not isinstance(link, dict):
                continue
            try:
                url = official_url(link.get("url"))
            except ServiceError:
                continue
            label = clean_text(link.get("title"), 300).strip()
            kind = document_kind(url)
            if kind is None and re.search(r"פרוטוקול(?!ים)|\bprotocol\b", label, re.I):
                kind = "html"
            if kind and url != final and url not in seen:
                seen.add(url)
                documents.append({"url": url, "title": label or urllib.parse.unquote(urllib.parse.urlsplit(url).path.rsplit("/", 1)[-1])[:300], "format": kind})
        warnings = []
        if documents:
            warnings.append("נקרא דף המכיל קישורים למסמכים. יש לבחור מסמך כדי לקרוא את הפרוטוקול עצמו")
        if snapshot.get("linksTruncated") or len(documents) > MAX_DOCUMENTS:
            warnings.append("רשימת הקישורים קוצרה; ניתן לפתוח את דף המקור לקישורים נוספים")
        if not text.strip():
            warnings.append("לא התקבל טקסט גלוי מהדף. לא הוסק ממנו תוכן של פרוטוקול")
        kind = "html" if not documents and protocol_markers(text) else "page"
        result = result_for(source, final, title, text, kind,
                            truncated=bool(snapshot.get("truncated") or snapshot.get("linksTruncated") or len(documents) > MAX_DOCUMENTS),
                            documents=documents, warnings=warnings)
    finally:
        # A new anonymous session is never reused. Attempt cleanup even after errors.
        active_error = sys.exc_info()[0] is not None
        try:
            grid.close()
        except ServiceError:
            if not active_error:
                raise ServiceError(502, "selenium_cleanup_failed", "סגירת הפעלת הדפדפן נכשלה; יש לבדוק את שרת Selenium") from None
    return result


def download_document(source, *, open_url=None, deadline=None):
    deadline = deadline if deadline is not None else time.monotonic() + WORK_SECONDS
    open_url = open_url or urllib.request.build_opener(NoRedirect()).open
    url = source
    for _hop in range(6):
        request = urllib.request.Request(url, headers={"Accept": "application/pdf, application/vnd.openxmlformats-officedocument.wordprocessingml.document, application/msword", "User-Agent": "MKPromisesProtocolReader/1", "Accept-Encoding": "identity"})
        try:
            with open_url(request, timeout=remaining(deadline, 25)) as response:
                if response.status in BLOCKED_STATUSES:
                    raise source_blocked()
                if response.status != 200:
                    raise ServiceError(502, "source_http_error", "הורדת המסמך מאתר הכנסת נכשלה")
                try:
                    final = official_url(response.geturl())
                except ServiceError:
                    raise ServiceError(403, "redirect_denied", "המסמך הפנה לכתובת שאינה מותרת לקריאה") from None
                length = response.headers.get("Content-Length", "")
                if length.isdigit() and int(length) > MAX_DOWNLOAD_BYTES:
                    raise ServiceError(413, "document_too_large", "ניתן לקרוא מסמך בגודל של עד 12 MiB")
                raw = bounded_read(response, MAX_DOWNLOAD_BYTES, deadline)
                if len(raw) > MAX_DOWNLOAD_BYTES:
                    raise ServiceError(413, "document_too_large", "ניתן לקרוא מסמך בגודל של עד 12 MiB")
                if blocked_page(final, "", raw[:20000].decode("utf-8", errors="replace")):
                    raise source_blocked()
                return raw, final
        except urllib.error.HTTPError as error:
            status, location = error.code, error.headers.get("Location")
            error.close()
            if status in BLOCKED_STATUSES:
                raise source_blocked() from None
            if status in {301, 302, 303, 307, 308} and location:
                try:
                    url = official_url(urllib.parse.urljoin(url, location))
                except ServiceError:
                    raise ServiceError(403, "redirect_denied", "המסמך הפנה לכתובת שאינה מותרת לקריאה") from None
                continue
            raise ServiceError(502, "source_http_error", "הורדת המסמך מאתר הכנסת נכשלה") from None
        except (TimeoutError, socket.timeout):
            raise ServiceError(504, "download_timeout", "הורדת המסמך ארכה זמן רב מדי") from None
        except (urllib.error.URLError, OSError):
            raise ServiceError(502, "download_failed", "לא ניתן להוריד את המסמך מאתר הכנסת") from None
    raise ServiceError(502, "too_many_redirects", "המקור הפנה לכתובות נוספות פעמים רבות מדי")


def detect_document(raw):
    if raw.startswith(b"%PDF-"):
        return "pdf"
    if raw.startswith(b"PK\x03\x04"):
        try:
            with zipfile.ZipFile(io.BytesIO(raw)) as archive:
                if "word/document.xml" in archive.namelist():
                    return "docx"
        except (zipfile.BadZipFile, OSError):
            pass
    if raw.startswith(b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"):
        return "doc"
    raise ServiceError(415, "unsupported_document", "הקובץ אינו PDF, DOCX או DOC נתמך; לא הופק ממנו טקסט")


def extract_document(raw, kind):
    """Runs only in a bounded worker process in production; directly testable with fixtures."""
    title, warnings, pieces, count, truncated = "", [], [], 0, False

    def add(text):
        nonlocal count, truncated
        text = clean_text(text, MAX_TEXT_CHARS + 1)
        space = MAX_TEXT_CHARS - count
        if len(text) > space:
            text, truncated = text[:space], True
        pieces.append(text)
        count += len(text) + 1
        return count >= MAX_TEXT_CHARS

    try:
        if kind == "pdf":
            from pypdf import PdfReader
            reader = PdfReader(io.BytesIO(raw), strict=False)
            if reader.is_encrypted:
                raise ServiceError(422, "encrypted_document", "המסמך מוצפן ולא ניתן לקריאה ללא הרשאה נוספת")
            title = str((reader.metadata or {}).get("/Title", ""))
            empty = 0
            for index, page in enumerate(reader.pages):
                if index >= 800:
                    truncated = True
                    break
                text = page.extract_text() or ""
                empty += not bool(text.strip())
                if add(text):
                    truncated = truncated or index + 1 < len(reader.pages)
                    break
            if empty:
                warnings.append("בחלק מהעמודים לא חולץ טקסט; אם אלה עמודים סרוקים נדרש OCR לקריאתם")
        elif kind == "docx":
            from docx import Document
            from docx.table import Table
            with zipfile.ZipFile(io.BytesIO(raw)) as archive:
                entries = archive.infolist()
                if len(entries) > 2048 or sum(x.file_size for x in entries) > 48 * 1024 * 1024:
                    raise ServiceError(413, "expanded_document_too_large", "תוכן המסמך הפרוס גדול מדי לקריאה")
            document = Document(io.BytesIO(raw))
            title = document.core_properties.title or ""
            for item in document.iter_inner_content():
                text = ("\n".join("\t".join(cell.text for cell in row.cells) for row in item.rows)
                        if isinstance(item, Table) else item.text)
                if add(text):
                    truncated = True
                    break
        elif kind == "doc":
            executable = shutil.which("antiword")
            if executable is None:
                raise ServiceError(415, "doc_reader_missing", "קריאת DOC ישן דורשת antiword בשרת; לא הופק טקסט מהמסמך")
            with tempfile.TemporaryDirectory(prefix="mk-protocol-") as directory:
                path = Path(directory) / "document.doc"
                path.write_bytes(raw)
                with tempfile.TemporaryFile() as output:
                    run = subprocess.run([executable, "-m", "UTF-8.txt", str(path)], stdout=output,
                                         stderr=subprocess.DEVNULL, timeout=20, check=False)
                    if run.returncode:
                        raise ServiceError(422, "unreadable_document", "לא ניתן לחלץ טקסט ממסמך DOC זה")
                    output.seek(0)
                    text = output.read(MAX_TEXT_CHARS * 4 + 1).decode("utf-8", errors="replace")
                    add(text)
        else:
            raise ServiceError(415, "unsupported_document", "סוג המסמך אינו נתמך")
    except ServiceError:
        raise
    except ImportError:
        raise ServiceError(503, "reader_dependency_missing", "רכיב קריאת המסמך אינו מותקן בשרת") from None
    except (subprocess.TimeoutExpired, TimeoutError):
        raise ServiceError(504, "extraction_timeout", "חילוץ הטקסט ארך זמן רב מדי") from None
    except Exception:
        raise ServiceError(422, "unreadable_document", "לא ניתן לחלץ טקסט תקין מהמסמך") from None
    text = "\n".join(pieces)[:MAX_TEXT_CHARS]
    if not text.strip():
        warnings = ["לא חולץ טקסט מהמסמך. אם הוא סרוק נדרש OCR; לא הוסק ממנו תוכן של פרוטוקול"]
    return {"text": text, "title": clean_text(title, 300), "truncated": truncated, "warnings": warnings}


def extract_isolated(raw, kind, deadline):
    process = None
    try:
        timeout = remaining(deadline, 25)
        process = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "--extract", kind],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                   start_new_session=os.name == "posix")
        output, _ = process.communicate(raw, timeout=timeout)
        if process.returncode or len(output) > MAX_RESPONSE_BYTES:
            raise ValueError()
        value = strict_json(output.decode("utf-8"))
        if "error" in value:
            error = value["error"]
            raise ServiceError(error["status"], error["code"], error["message"])
        if not isinstance(value.get("text"), str) or len(value["text"]) > MAX_TEXT_CHARS:
            raise ValueError()
        return value
    except subprocess.TimeoutExpired:
        if process is not None:
            if os.name == "posix":
                os.killpg(process.pid, signal.SIGKILL)
            else:
                process.kill()
            process.communicate()
        raise ServiceError(504, "extraction_timeout", "חילוץ הטקסט ארך זמן רב מדי") from None
    except (ValueError, UnicodeError, KeyError, OSError):
        raise ServiceError(422, "unreadable_document", "לא ניתן לחלץ טקסט תקין מהמסמך") from None


def read_protocol(url, config):
    source = official_url(url)
    deadline = time.monotonic() + WORK_SECONDS
    if document_kind(source) is None:
        return read_page(source, config, deadline=deadline)
    raw, final = download_document(source, deadline=deadline)
    kind = detect_document(raw)
    extracted = extract_isolated(raw, kind, deadline)
    title = extracted["title"] or urllib.parse.unquote(urllib.parse.urlsplit(final).path.rsplit("/", 1)[-1])
    return result_for(source, final, title, extracted["text"], kind,
                      truncated=extracted["truncated"], warnings=extracted["warnings"])


class ProtocolHTTPServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, config, reader=read_protocol):
        self.config, self.reader = config, reader
        self.read_slots = threading.BoundedSemaphore(2)
        super().__init__(address, ProtocolHandler)

    def handle_error(self, request, client_address):
        pass


class ProtocolHandler(BaseHTTPRequestHandler):
    server_version, sys_version = "ProtocolService/1", ""

    def setup(self):
        super().setup()
        self.connection.settimeout(10)
        self.cors_origin = None

    def log_message(self, format, *args):
        pass  # No tokens, document text, source queries, or Grid credentials in logs.

    def send_json(self, status, value, *, preflight=False):
        body = b"" if status == 204 else json_bytes(value)
        if len(body) > MAX_RESPONSE_BYTES:
            status, body = 502, json_bytes({"error": {"code": "response_too_large", "message": "התשובה גדולה מדי"}})
        self.send_response(status)
        for key, val in {"Content-Type": "application/json; charset=utf-8", "Content-Length": str(len(body)),
                         "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", "Vary": "Origin", "Connection": "close"}.items():
            self.send_header(key, val)
        self.close_connection = True
        if self.cors_origin is not None:
            self.send_header("Access-Control-Allow-Origin", self.cors_origin)
        if preflight:
            self.send_header("Access-Control-Allow-Methods", "POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
            self.send_header("Access-Control-Max-Age", "600")
        if status == 405:
            self.send_header("Allow", "POST, OPTIONS")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def fail(self, error):
        self.send_json(error.status, {"error": {"code": error.code, "message": error.message}})

    def route_origin(self):
        if self.path != "/api/protocols":
            raise ServiceError(404, "not_found", "הכתובת אינה קיימת")
        origins = self.headers.get_all("Origin", [])
        if len(origins) > 1 or (origins and origins[0] not in self.server.config.allowed_origins):
            raise ServiceError(403, "origin_denied", "מקור הבקשה אינו מורשה")
        self.cors_origin = origins[0] if origins else None

    def auth(self):
        token = self.server.config.api_token
        if token:
            values = self.headers.get_all("Authorization", [])
            parts = values[0].split(" ", 1) if len(values) == 1 else []
            supplied = parts[1] if len(parts) == 2 and parts[0].lower() == "bearer" else ""
            if not hmac.compare_digest(supplied.encode("utf-8"), token.encode("utf-8")):
                raise ServiceError(401, "unauthorized", "נדרש אסימון גישה תקין לשירות הפרוטוקולים")

    def body_url(self):
        if self.headers.get("Transfer-Encoding"):
            raise ServiceError(400, "invalid_transfer_encoding", "נדרש Content-Length")
        lengths = self.headers.get_all("Content-Length", [])
        if len(lengths) != 1 or not re.fullmatch(r"[0-9]{1,10}", lengths[0]):
            raise ServiceError(411, "length_required", "נדרש Content-Length תקין")
        length = int(lengths[0])
        if length > MAX_BODY_BYTES:
            raise ServiceError(413, "request_too_large", "גוף הבקשה גדול מדי")
        if (len(self.headers.get_all("Content-Type", [])) != 1 or self.headers.get_content_type() != "application/json"
                or self.headers.get_content_charset("utf-8").lower() != "utf-8"
                or self.headers.get("Content-Encoding", "identity").lower() != "identity"):
            raise ServiceError(415, "unsupported_media_type", "נדרש application/json בקידוד UTF-8")
        try:
            raw = self.rfile.read(length)
        except (TimeoutError, socket.timeout):
            raise ServiceError(408, "request_timeout", "קבלת הבקשה ארכה זמן רב מדי") from None
        if len(raw) != length:
            raise ServiceError(400, "incomplete_request", "הבקשה לא התקבלה במלואה")
        return validate_request(raw)

    def do_POST(self):
        try:
            self.route_origin()
            self.auth()
            url = self.body_url()
            if not self.server.read_slots.acquire(blocking=False):
                raise ServiceError(503, "service_busy", "שתי קריאות כבר מתבצעות. נסו שוב בעוד רגע")
            try:
                result = self.server.reader(url, self.server.config)
            finally:
                self.server.read_slots.release()
            self.send_json(200, result)
        except ServiceError as error:
            self.fail(error)
        except Exception:
            self.fail(ServiceError(500, "internal_error", "קריאת המקור נכשלה"))

    def do_OPTIONS(self):
        try:
            self.route_origin()
            if self.headers.get("Access-Control-Request-Method", "") != "POST":
                raise ServiceError(405, "method_not_allowed", "מותרת קריאה באמצעות POST בלבד")
            headers = {x.strip().lower() for x in self.headers.get("Access-Control-Request-Headers", "").split(",") if x.strip()}
            if headers - {"authorization", "content-type"}:
                raise ServiceError(403, "headers_denied", "כותרות הבקשה אינן מורשות")
            self.send_json(204, None, preflight=True)
        except ServiceError as error:
            self.fail(error)

    def unsupported(self):
        try:
            self.route_origin()
            raise ServiceError(405, "method_not_allowed", "מותרת קריאה באמצעות POST בלבד")
        except ServiceError as error:
            self.fail(error)

    do_GET = do_HEAD = do_PUT = do_PATCH = do_DELETE = do_TRACE = do_CONNECT = unsupported


def extraction_worker(kind):
    logging.disable(logging.CRITICAL)
    try:
        # Bound parser memory/CPU on Unix; the parent enforces elapsed time everywhere.
        try:
            import resource
            resource.setrlimit(resource.RLIMIT_AS, (768 * 1024 * 1024,) * 2)
            resource.setrlimit(resource.RLIMIT_CPU, (22, 22))
            resource.setrlimit(resource.RLIMIT_FSIZE, (2 * 1024 * 1024,) * 2)
        except (ImportError, ValueError, OSError):
            pass
        raw = sys.stdin.buffer.read(MAX_DOWNLOAD_BYTES + 1)
        if len(raw) > MAX_DOWNLOAD_BYTES:
            raise ServiceError(413, "document_too_large", "המסמך גדול מדי")
        value = extract_document(raw, kind)
    except ServiceError as error:
        value = {"error": {"status": error.status, "code": error.code, "message": error.message}}
    sys.stdout.buffer.write(json_bytes(value))


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "--extract" and sys.argv[2] in FORMATS:
        extraction_worker(sys.argv[2])
        return
    try:
        config = Config.from_env()
        server = ProtocolHTTPServer((config.host, config.port), config)
    except (ValueError, OSError) as error:
        print("Protocol service configuration error: " + str(error), file=sys.stderr)
        raise SystemExit(2) from None
    print(f"Protocol reader listening on {config.host}:{config.port}; Selenium configured", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()

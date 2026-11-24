"""Bounded public SEC acquisition. Raw evidence only, never replay/P&L approval.

Run: python -B tools/team18_acquire.py
No credentials, environment files, broker, database, or production application.
Exactly four fixed requests, no retries/redirects, at most one request per second.
SEC API documentation: https://www.sec.gov/search-filings/edgar-application-programming-interfaces
"""

import hashlib
import json
import math
import re
import time
import uuid
from datetime import date, datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener


COMPANIES = {"AAPL": "0000320193", "MSFT": "0000789019"}
DESTINATION = Path(__file__).resolve().parent / "team18_data"
MAX_BYTES = 30 * 1024 * 1024
USER_AGENT = "my-vue-project-offline-research/1.0"


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise HTTPError(req.full_url, code, "Redirect refused", headers, fp)


def now():
    return datetime.now(timezone.utc).isoformat()


def endpoint(symbol, kind):
    cik = COMPANIES[symbol]
    if kind == "companyfacts":
        return f"https://data.sec.gov/api/xbrl/companyfacts/CIK{cik}.json"
    if kind == "submissions":
        return f"https://data.sec.gov/submissions/CIK{cik}.json"
    raise ValueError("Only fixed public SEC data endpoints are supported")


def summarize(raw, symbol, kind):
    data = json.loads(raw)
    if str(data.get("cik", "")).zfill(10) != COMPANIES[symbol]:
        raise ValueError("CIK mismatch")
    if kind == "companyfacts":
        facts = data.get("facts")
        if not isinstance(facts, dict) or not facts:
            raise ValueError("Missing facts")
        records = []
        for concepts in facts.values():
            for concept in concepts.values():
                for values in concept.get("units", {}).values():
                    records.extend(values)
        if not records:
            raise ValueError("Empty fact records")
        for row in records:
            date.fromisoformat(row["filed"])
            date.fromisoformat(row["end"])
            if not re.fullmatch(r"\d{10}-\d{2}-\d{6}", row["accn"]):
                raise ValueError("Invalid fact accession")
            if isinstance(row["val"], bool) or not isinstance(row["val"], (int, float)) or not math.isfinite(row["val"]):
                raise ValueError("Invalid fact value")
        filed = sorted(row["filed"] for row in records if row.get("filed"))
        return {"taxonomy_count": len(facts), "fact_record_count": len(records),
                "first_filed": filed[0] if filed else None,
                "last_filed": filed[-1] if filed else None,
                "point_in_time_selected": False}
    recent = data.get("filings", {}).get("recent", {})
    if not isinstance(recent.get("accessionNumber"), list):
        raise ValueError("Missing filing history")
    accessions = recent["accessionNumber"]
    if not accessions or any(not re.fullmatch(r"\d{10}-\d{2}-\d{6}", value) for value in accessions):
        raise ValueError("Empty or invalid filing history")
    for field in ("filingDate", "acceptanceDateTime"):
        if not isinstance(recent.get(field), list) or len(recent[field]) != len(accessions):
            raise ValueError("Filing timestamp arrays are incomplete")
    for value in recent["filingDate"]:
        date.fromisoformat(value)
    for value in recent["acceptanceDateTime"]:
        if datetime.fromisoformat(value).tzinfo is None:
            raise ValueError("Filing acceptance time has no timezone")
    return {"recent_filings": len(recent["accessionNumber"]),
            "additional_history_files_not_downloaded": len(data["filings"].get("files", [])),
            "acceptance_datetime_count": len(recent.get("acceptanceDateTime", [])),
            "point_in_time_selected": False}


def acquire_one(folder, symbol, kind, opener=None):
    url = endpoint(symbol, kind)
    entry = {"symbol": symbol, "kind": kind, "url": url, "requested_at_utc": now(),
             "status": "FAILED", "http_status": None, "sha256": None,
             "raw_file": None, "historical_provider_snapshot": False}
    try:
        request = Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"}, method="GET")
        with (opener or build_opener(NoRedirect())).open(request, timeout=30) as response:
            entry["http_status"] = response.status
            if response.status != 200:
                raise ValueError("Non-success response")
            raw = response.read(MAX_BYTES + 1)
        if len(raw) > MAX_BYTES:
            raise ValueError("Response exceeds size bound")
        summary = summarize(raw, symbol, kind)
        name = f"{symbol}_{kind}.json"
        with (folder / name).open("xb") as output:
            output.write(raw)
        entry.update(status="ACQUIRED_RAW_PARTIAL", sha256=hashlib.sha256(raw).hexdigest(),
                     raw_file=name, bytes=len(raw), summary=summary)
    except HTTPError as error:
        entry.update(error="HTTP_ERROR", http_status=error.code)
    except (URLError, TimeoutError, OSError) as error:
        # Never copy response bodies, proxy addresses, authentication or local paths.
        entry.update(error="NETWORK_OR_IO_ERROR", error_type=type(error).__name__)
        reason = error.reason if isinstance(error, URLError) else error
        entry["reason_type"] = type(reason).__name__
        for field in ("errno", "winerror"):
            code = getattr(reason, field, None)
            if isinstance(code, int):
                entry[field] = code
    except (ValueError, KeyError, TypeError, AttributeError) as error:
        entry.update(error="INVALID_PROVIDER_RESPONSE", error_type=type(error).__name__)
    entry["finished_at_utc"] = now()
    return entry


def run():
    folder = DESTINATION / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8])
    folder.mkdir(parents=True, exist_ok=False)
    entries = []
    for symbol in COMPANIES:
        for kind in ("submissions", "companyfacts"):
            if entries:
                time.sleep(1)
            entries.append(acquire_one(folder, symbol, kind))
    manifest = {"schema_version": 1, "created_at_utc": now(), "status": "PARTIAL_ACQUISITION_NOT_REPLAY_READY",
                "acquired_count": sum(e["status"] == "ACQUIRED_RAW_PARTIAL" for e in entries),
                "requested_count": len(entries), "performance_allowed": False, "returns_computed": False,
                "source_documentation": "https://www.sec.gov/search-filings/edgar-application-programming-interfaces",
                "collector_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                "limitations": ["Two-company acquisition probe; not the complete historical candidate universe.",
                    "Current SEC aggregates contain later filings and amendments; no historical selection has been applied.",
                    "SEC facts do not reproduce historical Yahoo PER/ROE values, lookup failures or caches.",
                    "Additional submissions history pages are listed but not fetched.",
                    "Historical intraday ranks, quotes, membership and execution accounting remain missing.",
                    "No replay input, simulated fills, returns, benchmark or production mutation is produced."],
                "requests": entries}
    (folder / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"manifest": str(folder / "manifest.json"), "acquired_count": manifest["acquired_count"],
                      "requested_count": 4, "status": manifest["status"], "performance_allowed": False}))
    return 0 if manifest["acquired_count"] == 4 else 2


if __name__ == "__main__":
    raise SystemExit(run())

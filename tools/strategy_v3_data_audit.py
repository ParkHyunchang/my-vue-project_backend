"""Validate TEAM-12 snapshot integrity without computing investment returns."""
import csv
import hashlib
import json
import math
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).parent / "strategy_v3_data"


def audit(folder):
    manifest = json.loads((folder / "manifest.json").read_text())
    if hashlib.sha256((folder / "daily.csv").read_bytes()).hexdigest() != manifest["daily_sha256"]:
        raise ValueError("Combined CSV hash mismatch")
    with (folder / "daily.csv").open(newline="") as stream:
        rows = list(csv.DictReader(stream))
    keys = [(row["date"], row["symbol"]) for row in rows]
    if len(set(keys)) != len(rows) or keys != sorted(keys):
        raise ValueError("Duplicate/unsorted bars")
    combined = dict(zip(keys, rows))
    reference = None
    for entry in manifest["files"]:
        for kind in ("raw", "csv"):
            if hashlib.sha256((folder / entry[kind + "_file"]).read_bytes()).hexdigest() != entry[kind + "_sha256"]:
                raise ValueError("Original file hash mismatch")
        raw = json.loads((folder / entry["raw_file"]).read_bytes())["chart"]["result"][0]
        symbol = entry["symbol"]
        quote = raw["indicators"]["quote"][0]
        adj = raw["indicators"]["adjclose"][0]["adjclose"]
        dates = [datetime.fromtimestamp(value, timezone.utc).date().isoformat() for value in raw["timestamp"]]
        if reference is None:
            reference = dates
        if dates != reference or dates != sorted(set(dates)) or len(dates) != entry["rows"]:
            raise ValueError("Dates differ/duplicate/count mismatch")
        for i, day in enumerate(dates):
            row = combined[day, symbol]
            factor = 1 if symbol == "^SP500TR" else adj[i] / quote["close"][i]
            for field in ("open", "high", "low", "close"):
                value = float(row[field])
                if not math.isfinite(value) or value <= 0 or not math.isclose(value, quote[field][i] * factor, rel_tol=1e-12):
                    raise ValueError("Price adjustment mismatch")
            if float(row["raw_close"]) != quote["close"][i] or float(row["volume"]) != quote["volume"][i]:
                raise ValueError("Quote/volume mismatch")
    if len(rows) != len(reference) * len(manifest["files"]):
        raise ValueError("Unexpected rows")
    return {"folder": str(folder), "status": "PASS", "rows": len(rows),
            "sessions_per_symbol": len(reference), "first_date": reference[0], "last_date": reference[-1],
            "checks": ["original hashes", "combined hash", "adjusted OHLC raw reconstruction", "quote and volume", "identical ordered unique sessions"],
            "strategy_results_computed_or_opened": False}


if __name__ == "__main__":
    report = [audit(ROOT)]
    (ROOT / "data-audit.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))

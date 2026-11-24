"""Independent offline checks of the raw Yahoo snapshot and replay CSV."""

import csv
import hashlib
import json
import math
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).parent


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run():
    data_root = ROOT / "market_data"
    manifest_bytes = (data_root / "manifest.json").read_bytes()
    manifest = json.loads(manifest_bytes)
    prepared = json.loads((data_root / "prepared.json").read_text())
    require(hashlib.sha256(manifest_bytes).hexdigest() == prepared["source_manifest_sha256"], "manifest hash")
    daily_bytes = (data_root / "daily.csv").read_bytes()
    require(hashlib.sha256(daily_bytes).hexdigest() == prepared["sha256"], "daily hash")
    with (data_root / "daily.csv").open(newline="") as stream:
        daily = list(csv.DictReader(stream))
    require(len(daily) == prepared["rows"], "daily row count")
    keys = [(row["date"], row["symbol"]) for row in daily]
    require(len(keys) == len(set(keys)) and keys == sorted(keys), "duplicate/unordered daily rows")
    lookup = dict(zip(keys, daily))
    reference_dates = None
    symbols = []
    for entry in manifest["files"]:
        symbol = entry["symbol"]
        for kind in ("raw", "csv"):
            contents = (data_root / entry[kind + "_file"]).read_bytes()
            require(hashlib.sha256(contents).hexdigest() == entry[kind + "_sha256"], f"{symbol} {kind} hash")
        raw = json.loads((data_root / entry["raw_file"]).read_bytes())["chart"]["result"][0]
        quotes = raw["indicators"]["quote"][0]
        adj = raw["indicators"]["adjclose"][0]["adjclose"]
        dates = [datetime.fromtimestamp(value, timezone.utc).date().isoformat() for value in raw["timestamp"]]
        require(len(dates) == entry["rows"], f"{symbol} raw count")
        require(dates == sorted(set(dates)), f"{symbol} raw order/duplicates")
        require(dates[0] == entry["first_date"] and dates[-1] == entry["last_date"], f"{symbol} bounds")
        if reference_dates is None:
            reference_dates = dates
        require(dates == reference_dates, f"{symbol} missing benchmark dates")
        factors = []
        for i, day in enumerate(dates):
            row = lookup[(day, symbol)]
            factor = adj[i] / quotes["close"][i]
            factors.append(factor)
            require(math.isfinite(factor) and factor > 0, f"{symbol} {day} adjustment")
            for field in ("open", "high", "low", "close"):
                value = float(row[field])
                expected = quotes[field][i] * factor
                require(math.isfinite(value) and value > 0, f"{symbol} {day} invalid {field}")
                require(math.isclose(value, expected, rel_tol=1e-12), f"{symbol} {day} adjusted {field}")
                require(float(row["raw_" + field]) == quotes[field][i], f"{symbol} {day} quote {field}")
            require(float(row["close"]) == adj[i], f"{symbol} {day} adjclose")
            opening, high, low, close = (float(row[field]) for field in ("open", "high", "low", "close"))
            require(low <= min(opening, close) + 1e-6 and high >= max(opening, close) - 1e-6, f"{symbol} {day} OHLC")
            require(float(row["volume"]) == quotes["volume"][i] and float(row["volume"]) >= 0, f"{symbol} {day} volume")
        symbols.append({"symbol": symbol, "rows": len(dates), "min_adjustment_factor": min(factors), "max_adjustment_factor": max(factors), "dividend_events": len(raw.get("events", {}).get("dividends", {})), "split_events": len(raw.get("events", {}).get("splits", {}))})
    require(set(row["symbol"] for row in daily) == set(entry["symbol"] for entry in manifest["files"]), "unexpected symbol")
    report = {
        "status": "PASS", "network_used": False,
        "rows": len(daily), "symbols": symbols,
        "first_date": reference_dates[0], "last_date": reference_dates[-1],
        "session_counts_by_year": dict(sorted(Counter(day[:4] for day in reference_dates).items())),
        "checks": ["all 24 original file hashes", "manifest hash", "derived daily hash", "raw-to-adjusted OHLC reconstruction", "raw quotes and volume preservation", "positive finite prices", "OHLC bounds", "unique ordered dates", "every symbol exactly matches SPY dates"],
        "limits": ["No independent exchange holiday calendar check; a date missing from every symbol remains undetectable.", "Provider values are not cross-certified against another data vendor.", "Retrospective adjusted prices and current-survivor sample do not establish historical tradability or strategy profitability."],
    }
    output = ROOT / "reports"
    output.mkdir(exist_ok=True)
    (output / "market-data-audit.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    run()

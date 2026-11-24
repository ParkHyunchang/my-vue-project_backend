"""Acquire TEAM-12's separate research snapshot. No strategy or return calculation."""
import csv
import hashlib
import io
import json
import math
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote
from urllib.request import Request, urlopen

ROOT = Path(__file__).parent / "strategy_v3_data"
START = "2006-01-01"
END_EXCLUSIVE = "2026-11-18"  # November 17 is the latest completed US session at request time
SYMBOLS = ("SPY", "QQQ", "GLD", "IEF", "^SP500TR")
EVALUATION_START = "2007-01-01"
PURPOSE = "TEAM-12 exploratory research, including previously observed 2026; no untouched holdout or strategy results computed"


def epoch(day):
    return int(datetime.fromisoformat(day).replace(tzinfo=timezone.utc).timestamp())


def main():
    ROOT.mkdir(exist_ok=True)
    if any(ROOT.iterdir()):
        raise SystemExit("Refusing to overwrite a validation snapshot")
    manifest = {"purpose": PURPOSE,
                "evaluation_start": EVALUATION_START, "requested_start": START,
                "requested_end_exclusive": END_EXCLUSIVE,
                "retrieved_at_utc": datetime.now(timezone.utc).isoformat(),
                "files": [], "provider": "Yahoo Finance public chart, unauthenticated",
                "warning": "Use this warmup and validation together. Do not concatenate adjusted prices from different download vintages without rebasing.",
                "sources": ["https://finance.yahoo.com/quote/SPY/history/", "https://finance.yahoo.com/quote/QQQ/history/", "https://finance.yahoo.com/quote/GLD/history/", "https://finance.yahoo.com/quote/IEF/history/", "https://finance.yahoo.com/quote/%5ESP500TR/history/"],
                "instrument_sources": ["https://www.spdrgoldshares.com/usa/gld/", "https://www.ishares.com/us/products/239456/ishares-710-year-treasury-bond-etf"],
                "cash_and_risk": "Only USD cash is the reserve; SPY/QQQ/GLD/IEF are all risky assets, including gold and Treasury bond ETFs."}
    shared_dates, combined = None, []
    for symbol in SYMBOLS:
        url = f"https://query1.finance.yahoo.com/v8/finance/chart/{quote(symbol, safe='')}?period1={epoch(START)}&period2={epoch(END_EXCLUSIVE)}&interval=1d&events=div%2Csplits"
        with urlopen(Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=30) as response:
            raw = response.read()
        data = json.loads(raw)["chart"]["result"][0]
        if data["meta"]["symbol"] != symbol or data["meta"]["currency"] != "USD":
            raise ValueError("Identity/currency mismatch")
        quotes = data["indicators"]["quote"][0]
        adjusted = data["indicators"].get("adjclose", [{"adjclose": quotes["close"]}])[0]["adjclose"]
        dates, rows = [], []
        for i, instant in enumerate(data["timestamp"]):
            day = datetime.fromtimestamp(instant, timezone.utc).date().isoformat()
            prices = [quotes[field][i] for field in ("open", "high", "low", "close")]
            if any(value is None or not math.isfinite(value) or value <= 0 for value in prices + [adjusted[i]]):
                raise ValueError("Incomplete/invalid price")
            if not START <= day < END_EXCLUSIVE or dates and day <= dates[-1]:
                raise ValueError("Invalid date sequence")
            factor = 1 if symbol == "^SP500TR" else adjusted[i] / quotes["close"][i]
            opening, high, low, close = [value * factor for value in prices]
            if max(opening, close) - high > high * 1e-10 or low - min(opening, close) > high * 1e-10:
                raise ValueError("Invalid OHLC")
            row = {"date": day, "symbol": symbol, "open": opening, "high": high, "low": low,
                   "close": close, "volume": quotes["volume"][i],
                   "raw_close": quotes["close"][i], "adjustment_factor": factor}
            rows.append(row)
            combined.append(row)
            dates.append(day)
        if shared_dates is None:
            shared_dates = dates
        if dates != shared_dates:
            raise ValueError("Symbol session mismatch; no forward filling")
        name = symbol.replace("^", "")
        stream = io.StringIO(newline="")
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader(); writer.writerows(rows)
        normalized = stream.getvalue().encode("utf-8")
        (ROOT / f"{name}.raw.json").write_bytes(raw)
        (ROOT / f"{name}.csv").write_bytes(normalized)
        manifest["files"].append({"symbol": symbol, "source_url": url,
            "raw_file": f"{name}.raw.json", "raw_sha256": hashlib.sha256(raw).hexdigest(),
            "csv_file": f"{name}.csv", "csv_sha256": hashlib.sha256(normalized).hexdigest(),
            "rows": len(rows), "first_date": dates[0], "last_date": dates[-1]})
        print(f"Saved {symbol}: {len(rows)} observations; no return calculation", flush=True)
    combined.sort(key=lambda row: (row["date"], row["symbol"]))
    with (ROOT / "daily.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(combined[0]), lineterminator="\n")
        writer.writeheader(); writer.writerows(combined)
    manifest["daily_sha256"] = hashlib.sha256((ROOT / "daily.csv").read_bytes()).hexdigest()
    manifest["results_opened"] = False
    (ROOT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()

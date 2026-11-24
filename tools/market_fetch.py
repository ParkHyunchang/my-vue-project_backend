"""Download a fixed research sample from Yahoo's public, unauthenticated chart endpoint."""

import csv
import hashlib
import io
import json
import math
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import Request, urlopen

SYMBOLS = ("SPY", "QQQ", "AAPL", "MSFT", "AMZN", "GOOGL", "NVDA", "META", "JPM", "XOM", "JNJ", "PG")
START = "2017-10-01"
END_EXCLUSIVE = "2026-01-01"
DESTINATION = Path(__file__).parent / "market_data"


def timestamp(day):
    return int(datetime.fromisoformat(day).replace(tzinfo=timezone.utc).timestamp())


def digest(content):
    return hashlib.sha256(content).hexdigest()


def normalize(raw, symbol):
    result = json.loads(raw)["chart"]
    if result.get("error"):
        raise ValueError(f"Provider error: {symbol}")
    data = result["result"][0]
    if data["meta"]["symbol"] != symbol or data["meta"]["currency"] != "USD":
        raise ValueError(f"Symbol/currency mismatch: {symbol}")
    quote = data["indicators"]["quote"][0]
    adjusted = data["indicators"]["adjclose"][0]["adjclose"]
    output = io.StringIO(newline="")
    writer = csv.writer(output, lineterminator="\n")
    writer.writerow(["date", "open", "high", "low", "close", "adj_close", "volume", "adjustment_factor", "adj_open", "adj_high", "adj_low"])
    dates = []
    for i, instant in enumerate(data["timestamp"]):
        day = datetime.fromtimestamp(instant, timezone.utc).date().isoformat()
        prices = [quote[key][i] for key in ("open", "high", "low", "close")] + [adjusted[i]]
        volume = quote["volume"][i]
        if any(value is None or not math.isfinite(value) or value <= 0 for value in prices):
            raise ValueError(f"Missing/nonpositive price: {symbol} {day}")
        if volume is None or volume < 0:
            raise ValueError(f"Missing/negative volume: {symbol} {day}")
        if not START <= day < END_EXCLUSIVE or dates and day <= dates[-1]:
            raise ValueError(f"Invalid date sequence: {symbol} {day}")
        opening, high, low, close, adj_close = prices
        if high + 1e-6 < max(opening, close, low) or low - 1e-6 > min(opening, close, high):
            raise ValueError(f"Inconsistent OHLC: {symbol} {day}")
        factor = adj_close / close
        writer.writerow([day, *prices, volume, factor, opening * factor, high * factor, low * factor])
        dates.append(day)
    if not dates:
        raise ValueError(f"Empty history: {symbol}")
    return output.getvalue().encode("utf-8"), dates, data


def main():
    DESTINATION.mkdir(exist_ok=True)
    # Existing snapshots are immutable; a repeat fetch must use a separately reviewed location.
    if any(DESTINATION.iterdir()):
        raise SystemExit("market_data is not empty; refusing to overwrite an existing snapshot")
    manifest = {
        "provider": "Yahoo Finance public chart endpoint",
        "retrieved_at_utc": datetime.now(timezone.utc).isoformat(),
        "requested_start": START, "requested_end_exclusive": END_EXCLUSIVE,
        "sample_selection": "Fixed 10 surviving large-cap examples plus SPY/QQQ, selected before computing returns; not historical index membership.",
        "benchmark": "SPY adjusted-close total-return proxy, NOT the official S&P 500 total return index",
        "adjustment": "Provider Adj Close/Close factor applied to OHLC; dividend/split adjusted retrospective research prices, not executable historical dollar prices.",
        "sources": [
            "https://finance.yahoo.com/quote/SPY/history/",
            "https://www.ssga.com/us/en/individual/etfs/state-street-spdr-sp-500-etf-trust-spy",
        ],
        "missing_inputs": ["point-in-time index constituents including delistings", "historical intraday bid/ask", "historical trade-value top-50", "point-in-time fundamentals", "intraday relative volume", "real fills/costs"],
        "files": [],
    }
    reference_dates = None
    for symbol in SYMBOLS:
        url = (f"https://query1.finance.yahoo.com/v8/finance/chart/{symbol}"
               f"?period1={timestamp(START)}&period2={timestamp(END_EXCLUSIVE)}&interval=1d&events=div%2Csplits")
        with urlopen(Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=30) as response:
            raw = response.read()
        normalized, dates, data = normalize(raw, symbol)
        if reference_dates is None:
            reference_dates = dates
        if dates != reference_dates:
            raise ValueError(f"Dates differ from SPY: {symbol}; no silent forward filling")
        raw_name, csv_name = f"{symbol}.raw.json", f"{symbol}.csv"
        (DESTINATION / raw_name).write_bytes(raw)
        (DESTINATION / csv_name).write_bytes(normalized)
        manifest["files"].append({
            "symbol": symbol, "url": url, "raw_file": raw_name, "raw_sha256": digest(raw),
            "csv_file": csv_name, "csv_sha256": digest(normalized), "rows": len(dates),
            "first_date": dates[0], "last_date": dates[-1],
            "currency": data["meta"]["currency"],
            "dividend_events": len(data.get("events", {}).get("dividends", {})),
            "split_events": len(data.get("events", {}).get("splits", {})),
        })
        print(f"{symbol}: {len(dates)} daily observations, SHA256 recorded", flush=True)
    (DESTINATION / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()

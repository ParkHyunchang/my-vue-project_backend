"""Fetch the provider's S&P 500 total-return index separately, preserving replay inputs."""
import csv
import hashlib
import io
import json
import math
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).parent / "market_data"
URL = "https://query1.finance.yahoo.com/v8/finance/chart/%5ESP500TR?period1=1506816000&period2=1767225600&interval=1d"


def main():
    if (ROOT / "SP500TR.raw.json").exists():
        raise SystemExit("Refusing to overwrite the existing index snapshot")
    with urlopen(Request(URL, headers={"User-Agent": "Mozilla/5.0"}), timeout=30) as response:
        raw = response.read()
    result = json.loads(raw)["chart"]["result"][0]
    if result["meta"]["symbol"] != "^SP500TR" or result["meta"]["currency"] != "USD":
        raise ValueError("Index identity mismatch")
    quotes = result["indicators"]["quote"][0]
    rows = []
    for i, instant in enumerate(result["timestamp"]):
        day = datetime.fromtimestamp(instant, timezone.utc).date().isoformat()
        value = quotes["close"][i]
        if value is None or not math.isfinite(value) or value <= 0:
            raise ValueError("Invalid index level")
        rows.append((day, value))
    with (ROOT / "SPY.csv").open(newline="") as stream:
        expected = [row["date"] for row in csv.DictReader(stream)]
    if [row[0] for row in rows] != expected:
        raise ValueError("Index dates differ from frozen market snapshot")
    buffer = io.StringIO(newline="")
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(["date", "sp500_total_return_index"])
    writer.writerows(rows)
    normalized = buffer.getvalue().encode("utf-8")
    (ROOT / "SP500TR.raw.json").write_bytes(raw)
    (ROOT / "SP500TR.csv").write_bytes(normalized)
    manifest = {"symbol": "^SP500TR", "provider_name": result["meta"].get("shortName"),
        "benchmark": "S&P 500 Total Return index, as distributed by Yahoo Finance; not SPY ETF proxy",
        "source_url": URL, "documentation_url": "https://finance.yahoo.com/quote/%5ESP500TR/history/",
        "retrieved_at_utc": datetime.now(timezone.utc).isoformat(), "currency": "USD",
        "raw_file": "SP500TR.raw.json", "raw_sha256": hashlib.sha256(raw).hexdigest(),
        "csv_file": "SP500TR.csv", "csv_sha256": hashlib.sha256(normalized).hexdigest(),
        "rows": len(rows), "first_date": rows[0][0], "last_date": rows[-1][0],
        "field": "quote.close; total return already embedded, do not add dividends or apply SPY adjustment factors",
        "limitation": "Provider-distributed index series; not independently reconciled against an S&P DJI licensed feed."}
    (ROOT / "SP500TR.manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()

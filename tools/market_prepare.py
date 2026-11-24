"""Verify the downloaded snapshot, then build the adjusted daily replay input offline."""

import csv
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).parent / "market_data"


def prepare():
    manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
    rows = []
    for entry in manifest["files"]:
        for kind in ("raw", "csv"):
            path = ROOT / entry[f"{kind}_file"]
            if hashlib.sha256(path.read_bytes()).hexdigest() != entry[f"{kind}_sha256"]:
                raise ValueError(f"Snapshot SHA256 mismatch: {path.name}")
        with (ROOT / entry["csv_file"]).open(newline="", encoding="utf-8") as stream:
            for row in csv.DictReader(stream):
                rows.append({
                    "date": row["date"], "symbol": entry["symbol"],
                    "open": row["adj_open"], "high": row["adj_high"],
                    "low": row["adj_low"], "close": row["adj_close"], "volume": row["volume"],
                    "raw_open": row["open"], "raw_high": row["high"],
                    "raw_low": row["low"], "raw_close": row["close"],
                    "adjustment_factor": row["adjustment_factor"],
                })
    rows.sort(key=lambda row: (row["date"], row["symbol"]))
    output = ROOT / "daily.csv"
    with output.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    verification = {
        "source_manifest_sha256": hashlib.sha256((ROOT / "manifest.json").read_bytes()).hexdigest(),
        "file": output.name, "sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
        "rows": len(rows), "adjusted_ohlc": True,
        "volume_is_provider_reported_not_dividend_adjusted": True,
        "network_used": False,
    }
    (ROOT / "prepared.json").write_text(json.dumps(verification, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(verification, indent=2))


if __name__ == "__main__":
    prepare()

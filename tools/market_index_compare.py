"""Compare unchanged replay equity with the separately acquired total-return index."""
import argparse
import csv
import hashlib
import json
from datetime import date
from pathlib import Path

ROOT = Path(__file__).parent / "market_data"


def compare(prefix):
    manifest = json.loads((ROOT / "SP500TR.manifest.json").read_text())
    for kind in ("raw", "csv"):
        if hashlib.sha256((ROOT / manifest[kind + "_file"]).read_bytes()).hexdigest() != manifest[kind + "_sha256"]:
            raise ValueError("Index hash mismatch")
    with (ROOT / "SP500TR.csv").open(newline="") as stream:
        levels = {row["date"]: float(row["sp500_total_return_index"]) for row in csv.DictReader(stream)}
    equity_path = Path(str(prefix) + "-equity.csv")
    with equity_path.open(newline="") as stream:
        rows = list(csv.DictReader(stream))
    if any(float(row["external_flow_usd"]) != 0 for row in rows):
        raise ValueError("Comparison requires this replay's zero-flow equity")
    peak, drawdown = levels[rows[0]["date"]], 0
    for row in rows:
        level = levels[row["date"]]
        peak = max(peak, level)
        drawdown = max(drawdown, 1 - level / peak)
    days = (date.fromisoformat(rows[-1]["date"]) - date.fromisoformat(rows[0]["date"])).days
    index_factor = levels[rows[-1]["date"]] / levels[rows[0]["date"]]
    strategy_factor = float(rows[-1]["equity_usd"]) / float(rows[0]["equity_usd"])
    result = {"benchmark": "S&P 500 Total Return (^SP500TR), Yahoo Finance-distributed index series",
        "start": rows[0]["date"], "end": rows[-1]["date"], "observations": len(rows),
        "equity_csv_sha256": hashlib.sha256(equity_path.read_bytes()).hexdigest(),
        "index_csv_sha256": manifest["csv_sha256"],
        "index_start_level": levels[rows[0]["date"]], "index_end_level": levels[rows[-1]["date"]],
        "strategy_total_return": strategy_factor - 1,
        "index_total_return": index_factor - 1, "index_cagr": index_factor ** (365.25 / days) - 1,
        "index_max_drawdown": drawdown,
        "excess_return_percentage_points": (strategy_factor - index_factor) * 100,
        "outperformed_index": strategy_factor > index_factor,
        "status": "LIMITED_DAILY_PROXY_NOT_LIVE_STRATEGY", "profitability_proven": False,
        "note": "Unchanged strategy equity; benchmark substitution only. All replay approximations and survivor-sample limitations still apply."}
    Path(str(prefix) + "-sp500tr.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("prefix", type=Path, nargs="+")
    args = parser.parse_args()
    for prefix in args.prefix:
        print(json.dumps(compare(prefix), indent=2))

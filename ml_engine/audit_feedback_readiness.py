"""Audit a Supabase stress_feedback CSV before model retraining.

The thresholds are conservative starting gates, not a claim that a sample size guarantees clinical
validity. Alert-only feedback is reported separately because it can calibrate positive alerts but
cannot reveal false negatives.
"""

from __future__ import annotations

import argparse
import csv
from collections import Counter
from pathlib import Path


def as_bool(value: str) -> bool:
    return value.strip().lower() in {"1", "true", "t", "yes"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", type=Path, help="CSV export of public.stress_feedback")
    parser.add_argument("--min-completed", type=int, default=500)
    parser.add_argument("--min-per-class", type=int, default=100)
    parser.add_argument("--min-users", type=int, default=30)
    args = parser.parse_args()

    with args.csv.open(newline="", encoding="utf-8-sig") as handle:
        rows = [row for row in csv.DictReader(handle) if row.get("responded_at")]

    labels = Counter(as_bool(row.get("confirmed_stressed", "")) for row in rows)
    sources = Counter(row.get("prompt_source", "unknown") for row in rows)
    users = {row.get("user_id", "") for row in rows if row.get("user_id")}

    print(f"completed labels : {len(rows)} (minimum {args.min_completed})")
    print(f"confirmed stress : {labels[True]}")
    print(f"not stressed     : {labels[False]}")
    print(f"distinct users   : {len(users)} (minimum {args.min_users})")
    print("prompt sources   : " + ", ".join(f"{key}={value}" for key, value in sources.items()))

    volume_ready = (
        len(rows) >= args.min_completed
        and labels[True] >= args.min_per_class
        and labels[False] >= args.min_per_class
        and len(users) >= args.min_users
    )
    has_unselected_samples = sources["periodic_check_in"] > 0

    if volume_ready:
        print("alert calibration: READY for a user-grouped evaluation")
    else:
        print("alert calibration: NOT READY")

    if volume_ready and has_unselected_samples:
        print("full retraining  : CANDIDATE DATASET; still validate drift, leakage and held-out users")
        return 0

    print("full retraining  : NOT READY")
    if not has_unselected_samples:
        print("reason           : alert-only sampling cannot measure false negatives")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())

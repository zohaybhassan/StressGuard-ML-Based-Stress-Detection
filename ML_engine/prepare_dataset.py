"""
Build the StressGuard training set from the raw sleep-health dataset.

Replaces the original Colab notebook, which is not reproducible here (it referenced
an input file that was never committed) and which introduced two defects:

  1. It z-scored Age / Heart Rate / Daily Steps / Sleep Duration. The exported models
     therefore expect standardized inputs, while the Android app sends raw physical
     units, so every live sample collapsed to a single prediction. RandomForest,
     XGBoost and CatBoost are all scale-invariant, so this script keeps raw units.

  2. It one-hot encoded *before* oversampling, so plain SMOTE interpolated the
     indicator columns and the results were rounded back to 0/1. That produced rows
     encoding people who cannot exist -- 462 with two occupations at once, 81 both
     Obese and Overweight, 504 of 2360 rows (21.4%) in total, concentrated in the
     rare classes. This script oversamples with SMOTENC on the intact categorical
     columns, which takes the majority category among neighbours instead.

Note on the resampling geometry: SMOTENC selects neighbours by Euclidean distance,
so on raw units Daily Steps (1000..16036) would swamp Sleep Duration (5.1..10).
The continuous columns are therefore standardized for the resampling step only and
inverted straight afterwards, so neighbour selection stays sensible while the output
remains in raw physical units.

Usage:
    python prepare_dataset.py
    python prepare_dataset.py --raw data/sleep_health_dataset.csv --out data/StressGuard_Iteration1_Raw_Units.csv
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Dict, List

import numpy as np
import pandas as pd
from imblearn.over_sampling import SMOTENC

HERE = Path(__file__).resolve().parent

TARGET_RAW = "Stress Level"
TARGET_OUT = "Target_Stress_Level"

CONTINUOUS: List[str] = ["Age", "Heart Rate", "Daily Steps", "Sleep Duration"]
CATEGORICAL: List[str] = ["Gender", "Occupation", "BMI Category"]

# Columns present in the raw file that the 22-feature model does not use.
DROP_ALWAYS: List[str] = ["Person ID", "Physical Activity Level"]

# Raw-unit grid to snap synthetic rows back onto, so they are shaped like real records.
ROUNDING: Dict[str, int] = {"Age": 0, "Heart Rate": 0, "Daily Steps": 0, "Sleep Duration": 1}


def load_raw(path: Path) -> pd.DataFrame:
    df = pd.read_csv(path)
    print(f"  loaded {path.name}: {df.shape[0]} rows x {df.shape[1]} columns")

    # The file carries a stray trailing comma in its header, which pandas reads as an
    # all-empty "Unnamed: N" column. Drop those plus the columns the model does not use.
    junk = [c for c in df.columns if c.startswith("Unnamed:") or df[c].notna().sum() == 0]
    drop = [c for c in DROP_ALWAYS if c in df.columns] + junk
    if drop:
        df = df.drop(columns=drop)
        print(f"  dropped {drop}")

    missing = [c for c in CONTINUOUS + CATEGORICAL + [TARGET_RAW] if c not in df.columns]
    if missing:
        raise KeyError(f"raw dataset is missing required columns: {missing}")

    if df.isna().any().any():
        bad = df.columns[df.isna().any()].tolist()
        raise ValueError(f"raw dataset has missing values in {bad}; clean before proceeding")
    return df


def compute_scaling(df: pd.DataFrame) -> Dict[str, Dict[str, float]]:
    """Population mean/std, matching sklearn StandardScaler (ddof=0)."""
    return {
        col: {"mean": float(df[col].mean()), "std": float(df[col].std(ddof=0))}
        for col in CONTINUOUS
    }


def resample(df: pd.DataFrame, scaling: Dict[str, Dict[str, float]], seed: int) -> pd.DataFrame:
    features = df[CONTINUOUS + CATEGORICAL].copy()
    target = df[TARGET_RAW].astype(int)

    # Standardize continuous columns for the duration of the resampling only.
    for col in CONTINUOUS:
        features[col] = (features[col] - scaling[col]["mean"]) / scaling[col]["std"]

    cat_idx = [features.columns.get_loc(c) for c in CATEGORICAL]
    sampler = SMOTENC(categorical_features=cat_idx, random_state=seed)
    x_res, y_res = sampler.fit_resample(features, target)

    print(f"  SMOTENC: {len(features)} -> {len(x_res)} rows "
          f"({len(x_res) - len(features)} synthetic)")

    # Back to raw physical units, then onto the grid real records sit on.
    for col in CONTINUOUS:
        x_res[col] = x_res[col] * scaling[col]["std"] + scaling[col]["mean"]
        digits = ROUNDING[col]
        x_res[col] = x_res[col].round(digits)
        if digits == 0:
            x_res[col] = x_res[col].astype(int)

    x_res[TARGET_OUT] = y_res.to_numpy() if hasattr(y_res, "to_numpy") else y_res
    return x_res


def encode(df: pd.DataFrame) -> pd.DataFrame:
    encoded = pd.get_dummies(df, columns=CATEGORICAL, drop_first=True, dtype=int)
    # get_dummies appends the indicators; put the target last to match the existing CSV.
    cols = [c for c in encoded.columns if c != TARGET_OUT] + [TARGET_OUT]
    return encoded[cols]


def verify_against_manifest(df: pd.DataFrame, manifest_path: Path) -> None:
    features = [c for c in df.columns if c != TARGET_OUT]
    if not manifest_path.is_file():
        print(f"  WARNING: no manifest at {manifest_path}; skipped column-order check")
        return

    expected = list(json.loads(manifest_path.read_text(encoding="utf-8"))["feature_names"])
    if features == expected:
        print(f"  column order matches {manifest_path.parent.name} manifest exactly "
              f"({len(features)} features)")
        return

    only_new = [c for c in features if c not in expected]
    only_old = [c for c in expected if c not in features]
    detail = ""
    if only_new or only_old:
        detail = f"\n  unexpected: {only_new}\n  missing:    {only_old}"
    else:
        firsts = [(i, a, b) for i, (a, b) in enumerate(zip(features, expected)) if a != b][:5]
        detail = "\n  same set, different order; first mismatches: " + str(firsts)
    raise AssertionError(
        f"produced columns do not match the manifest's feature_names.{detail}"
    )


def verify_encoding_sane(df: pd.DataFrame) -> None:
    """The check the original pipeline would have failed: no impossible category combinations."""
    groups = {
        "Occupation": [c for c in df.columns if c.startswith("Occupation_")],
        "BMI Category": [c for c in df.columns if c.startswith("BMI Category_")],
        "Gender": [c for c in df.columns if c.startswith("Gender_")],
    }
    problems = 0
    for name, cols in groups.items():
        if not cols:
            continue
        sums = df[cols].sum(axis=1)
        multi = int((sums > 1).sum())
        values = df[cols].to_numpy()
        non_binary = int(((values != 0) & (values != 1)).sum())
        baseline = int((sums == 0).sum())
        flag = "  <-- IMPOSSIBLE" if multi or non_binary else ""
        print(f"    {name:14s} baseline(all-zero)={baseline:5d}  exactly-one={int((sums == 1).sum()):5d}  "
              f"two-or-more={multi:3d}  non-binary={non_binary:3d}{flag}")
        problems += multi + non_binary
    if problems:
        raise AssertionError(f"{problems} impossible one-hot encodings produced")
    print("    no impossible category combinations")


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--raw", type=str, default="data/sleep_health_dataset.csv")
    parser.add_argument("--out", type=str, default="data/StressGuard_Iteration1_Raw_Units.csv")
    parser.add_argument(
        "--manifest",
        type=str,
        default="mobile_export/three_level_voting_wide_normal/stressguard_mobile_manifest.json",
        help="Manifest whose feature_names the output column order must match",
    )
    parser.add_argument(
        "--scaling-out",
        type=str,
        default="vital_scaling.json",
        help="Where to write the recovered mean/std. Not used by training any more, but it "
        "documents the original preprocessing and retires the guessed built-in values.",
    )
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    def resolve(value: str) -> Path:
        p = Path(value)
        return p if p.is_absolute() else HERE / p

    print("Loading raw dataset")
    raw = load_raw(resolve(args.raw))

    print("\nClass balance before resampling")
    counts = raw[TARGET_RAW].value_counts().sort_index()
    print(f"  levels {counts.index.min()}..{counts.index.max()}, "
          f"min={counts.min()} max={counts.max()} total={counts.sum()}")

    print("\nRecovering the original preprocessing statistics")
    scaling = compute_scaling(raw)
    for col, s in scaling.items():
        print(f"  {col:16s} mean={s['mean']:>12.6f}  std={s['std']:>12.6f}")
    scaling_path = resolve(args.scaling_out)
    scaling_path.write_text(json.dumps(scaling, indent=2), encoding="utf-8")
    print(f"  wrote {scaling_path.name}")

    print("\nOversampling with SMOTENC (categoricals kept intact)")
    resampled = resample(raw, scaling, args.seed)

    print("\nOne-hot encoding (drop_first: Female, Accountant and Normal become baselines)")
    encoded = encode(resampled)
    print(f"  {encoded.shape[0]} rows x {encoded.shape[1] - 1} features + target")

    print("\nVerifying")
    verify_against_manifest(encoded, resolve(args.manifest))
    verify_encoding_sane(encoded)

    balance = encoded[TARGET_OUT].value_counts()
    print(f"    class balance: {balance.min()}..{balance.max()} per level "
          f"across {balance.size} levels")

    print("\nRaw-unit ranges in the output (what Android will now send):")
    for col in CONTINUOUS:
        s = encoded[col]
        print(f"    {col:16s} min={s.min():>9.1f} max={s.max():>9.1f} mean={s.mean():>9.2f}")

    out_path = resolve(args.out)
    encoded.to_csv(out_path, index=False)
    print(f"\nWrote {out_path}")


if __name__ == "__main__":
    main()

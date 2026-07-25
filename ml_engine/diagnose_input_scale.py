"""
Diagnose the StressGuard input-scale defect.

The exported models were trained on StressGuard_Iteration1_Balanced_Data.csv, whose
numeric columns (Age, Heart Rate, Daily Steps, Sleep Duration) are z-scored. The Android
app's StressFeatureBuilder sends raw physical units (bpm, step counts, hours) instead.

This script demonstrates the consequence directly:

  Case 1  two sharply different RAW-unit samples  -> expect identical probabilities
  Case 2  the same two samples, z-scored          -> expect clearly different probabilities

Case 1 collapsing is the defect: raw values fall far outside every tree's split
thresholds, so all samples route to the same terminal leaf.

Usage:
    python diagnose_input_scale.py
    python diagnose_input_scale.py --bundle mobile_export/binary_voting_top3
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Dict, List, Tuple

import joblib
import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent

# Scaling stats are NOT persisted anywhere in the repo (the fitted StandardScaler was
# never saved). These are the documented guesses from predict_stress_interactive.py.
# The exact values do not matter for this diagnostic: the point is that *any* sane
# standardization pulls the inputs back into the trained range and restores sensitivity.
FALLBACK_SCALING: Dict[str, Tuple[float, float]] = {
    "Age": (38.0, 12.0),
    "Heart Rate": (72.0, 10.0),
    "Daily Steps": (7000.0, 3500.0),
    "Sleep Duration": (7.0, 1.2),
}

NUMERIC_COLUMNS = tuple(FALLBACK_SCALING)

# Two profiles that differ only in vitals, one plainly relaxed, one plainly stressed.
SCENARIOS: Dict[str, Dict[str, float]] = {
    "relaxed  (HR 60, 12000 steps, 8.5h sleep)": {
        "Age": 22, "Heart Rate": 60, "Daily Steps": 12000, "Sleep Duration": 8.5,
    },
    "stressed (HR 125, 1200 steps, 4.0h sleep)": {
        "Age": 22, "Heart Rate": 125, "Daily Steps": 1200, "Sleep Duration": 4.0,
    },
}

# Fixed categorical profile shared by both scenarios: Male, Student, Normal BMI.
CATEGORICAL: Dict[str, float] = {"Gender_Male": 1.0, "Occupation_Student": 1.0}


def build_row(feature_names: List[str], vitals: Dict[str, float]) -> Dict[str, float]:
    row = {name: 0.0 for name in feature_names}
    for name, value in {**vitals, **CATEGORICAL}.items():
        if name not in row:
            raise KeyError(f"{name!r} is not in the bundle's feature_names")
        row[name] = float(value)
    return row


def to_z(row: Dict[str, float]) -> Dict[str, float]:
    out = dict(row)
    for col, (mean, std) in FALLBACK_SCALING.items():
        out[col] = (row[col] - mean) / std
    return out


def frame(rows: List[Dict[str, float]], feature_names: List[str]) -> np.ndarray:
    # model_onnx_aligned was fitted on float32 NumPy (see export_mobile_model.py), so feed
    # it the same way the ONNX graphs are fed on Android: positional, no column names.
    return np.ascontiguousarray(
        pd.DataFrame(rows, columns=feature_names).to_numpy(dtype=np.float32)
    )


def report_case(title: str, probs: np.ndarray, labels: List[str], names: List[str]) -> bool:
    print(f"\n  {title}")
    for name, p in zip(names, probs):
        pretty = "  ".join(f"{lab}={val:.6f}" for lab, val in zip(labels, p))
        print(f"    {name}\n      {pretty}")
    spread = float(np.abs(probs[0] - probs[1]).max())
    collapsed = spread < 1e-6
    verdict = "IDENTICAL  <-- model is blind to the sensor change" if collapsed else "different"
    print(f"    max |delta| between the two = {spread:.9f}   [{verdict}]")
    return collapsed


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--bundle",
        type=str,
        default="mobile_export/three_level_voting_wide_normal",
        help="Bundle directory, relative to ml_engine/ or absolute",
    )
    args = parser.parse_args()

    bundle = Path(args.bundle)
    if not bundle.is_absolute():
        bundle = HERE / bundle
    joblib_path = bundle / "voting_top3_sklearn.joblib"
    if not joblib_path.is_file():
        raise FileNotFoundError(f"Missing {joblib_path}")

    payload = joblib.load(joblib_path)
    model = payload["model_onnx_aligned"]  # the estimator the ONNX files were exported from
    feature_names: List[str] = list(payload["feature_names"])
    # The binary bundle predates the exporter that records n_classes (same staleness as its
    # manifest, which also lacks n_classes/export_kind), so fall back to the fitted estimator.
    n_classes = int(payload.get("n_classes") or len(model.classes_))

    manifest_path = bundle / "stressguard_mobile_manifest.json"
    labels = [f"p{i}" for i in range(n_classes)]
    # Bundles built after the units fix declare their input contract. Older ones do not, and
    # those are the ones that expect z-scores.
    input_units = "z_scored (assumed: manifest predates input_units)"
    if manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        label_block = manifest.get("label", {})
        named = [label_block.get(f"class_{i}") for i in range(n_classes)]
        if all(named):
            labels = named
        input_units = manifest.get("input_units", input_units)

    expects_raw = input_units.startswith("raw")

    print("=" * 78)
    print("StressGuard input-scale diagnostic")
    print("=" * 78)
    print(f"  bundle        {bundle.name}")
    print(f"  features      {len(feature_names)}")
    print(f"  classes       {n_classes}  ({', '.join(labels)})")
    print(f"  input_units   {input_units}")
    print(f"  => the app should send {'RAW physical units' if expects_raw else 'Z-SCORED values'}")

    # Show what range the model was actually trained on, next to the values we send. Which CSV
    # that is depends on the bundle: raw-units bundles were trained on the raw-units file.
    train_csv = HERE / "data" / (
        "StressGuard_Iteration1_Raw_Units.csv" if expects_raw
        else "StressGuard_Iteration1_Balanced_Data.csv"
    )
    if train_csv.is_file():
        train = pd.read_csv(train_csv)
        print(f"\n  Trained-on ranges ({train_csv.name}) vs. the values sent below:")
        print(f"    {'column':<16} {'train min':>10} {'train max':>10}    {'raw sent':>10}")
        for col in NUMERIC_COLUMNS:
            raw_vals = [s[col] for s in SCENARIOS.values()]
            span = f"{min(raw_vals):g}..{max(raw_vals):g}"
            print(f"    {col:<16} {train[col].min():>10.3f} {train[col].max():>10.3f}    {span:>10}")

    names = list(SCENARIOS)
    raw_rows = [build_row(feature_names, SCENARIOS[n]) for n in names]

    print("\n" + "-" * 78)
    print("CASE 1  raw physical units  (what StressFeatureBuilder.kt sends today)")
    print("-" * 78)
    raw_probs = model.predict_proba(frame(raw_rows, feature_names))
    raw_collapsed = report_case("predict_proba:", raw_probs, labels, names)

    print("\n" + "-" * 78)
    print("CASE 2  same samples, z-scored  (what the models were trained on)")
    print("-" * 78)
    z_probs = model.predict_proba(frame([to_z(r) for r in raw_rows], feature_names))
    z_collapsed = report_case("predict_proba:", z_probs, labels, names)

    print("\n" + "=" * 78)
    if expects_raw:
        # A bundle trained on raw units: case 1 is the real code path and must respond.
        if not raw_collapsed:
            print("PASS")
            print("  This bundle expects raw physical units, and raw inputs move the output.")
            print("  Heart rate, steps and sleep now reach the model as intended, so no")
            print("  post-hoc sensor calibration is needed to make the gauge respond.")
            if not z_collapsed:
                print("\n  (Case 2 also varies, which is expected -- z-scored values are simply")
                print("   different numbers to a tree, not a special input. They are wrong,")
                print("   just not detectably wrong. Only the units contract distinguishes them.)")
        else:
            print("FAIL")
            print("  This bundle claims raw physical units but raw inputs produce one constant")
            print("  prediction. Do not ship it: the app's gauge would not respond to sensors.")
    else:
        if raw_collapsed and not z_collapsed:
            print("DIAGNOSIS CONFIRMED")
            print("  Raw-unit inputs collapse to one prediction; z-scored inputs separate.")
            print("  This bundle was trained on standardized columns, so sending raw bpm and")
            print("  step counts pushes every sample past the outermost split in every tree.")
        elif not raw_collapsed:
            print("DIAGNOSIS NOT REPRODUCED")
            print("  Raw inputs already vary the output. The root cause lies elsewhere.")
        else:
            print("INCONCLUSIVE: both cases collapsed. Check the scenarios and the bundle.")
    print("=" * 78)


if __name__ == "__main__":
    main()

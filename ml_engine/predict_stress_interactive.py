"""
Interactive terminal stress prediction for StressGuard.

Loads the trained voting ensemble (mobile export joblib or best_tuned_model.joblib),
prompts for inputs, and prints predicted stress level with class probabilities.

How vitals are entered depends on what the bundle was trained on, which its manifest states
in `input_units`:

  raw_physical  Current bundles. Enter years, bpm, steps and hours; they go to the model
                unchanged, exactly as the Android app sends them. --input-mode is ignored.

  (absent)      Bundles exported before the units fix, trained on standardized columns.
                --input-mode z    enter z-scores directly (roughly -3 to +3)
                --input-mode raw  enter everyday units, converted using --scaling-json,
                                  or vital_scaling.json beside this script, else the
                                  recovered constants below.

Sending raw units to a standardized model, or the reverse, does not fail. It returns a
confident, constant, wrong answer -- which is the defect diagnose_input_scale.py exists to
demonstrate. That is why this script follows the manifest rather than a flag.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import joblib
import numpy as np
import pandas as pd

# Reuse engineered feature helper when models were trained with --feature-mode engineered
try:
    from optimize_ml_engine import add_engineered_features
except ImportError:
    add_engineered_features = None  # type: ignore[misc, assignment]


def _here() -> Path:
    """This script's own directory, so paths hold regardless of the folder's name or case."""
    return Path(__file__).resolve().parent


def load_manifest(path: Path) -> Dict[str, Any]:
    with path.open(encoding="utf-8") as fp:
        return json.load(fp)


def load_model_bundle(path: Path) -> Tuple[Any, Dict[str, Any]]:
    """
    Returns (estimator, meta) where meta may include feature_names, label_mode, etc.
    """
    raw = joblib.load(path)
    if isinstance(raw, dict) and "model" in raw:
        model = raw.get("model_onnx_aligned") or raw["model"]
        meta = {
            "feature_names": raw.get("feature_names"),
            "feature_mode": raw.get("feature_mode", "base"),
            "label_mode": raw.get("label_mode", "three-level"),
            "three_level_scheme": raw.get("three_level_scheme"),
            "binary_threshold": raw.get("binary_threshold"),
            "n_classes": raw.get("n_classes"),
        }
        return model, meta
    meta = {
        "feature_names": None,
        "feature_mode": "base",
        "label_mode": "three-level",
        "three_level_scheme": "wide-normal",
        "binary_threshold": 7,
        "n_classes": None,
    }
    return raw, meta


def resolve_feature_order(model: Any, meta: Dict[str, Any]) -> List[str]:
    names = meta.get("feature_names")
    if names:
        return list(names)
    fin = getattr(model, "feature_names_in_", None)
    if fin is not None:
        return list(fin)
    raise ValueError(
        "Could not determine feature column order. Pass a mobile-export joblib "
        "or a sklearn model with feature_names_in_."
    )


def class_display_name(
    class_id: int,
    label_mode: str,
    three_level_scheme: Optional[str],
    n_classes: int,
    manifest: Optional[Dict[str, Any]],
) -> str:
    if manifest and "label" in manifest:
        lb = manifest["label"]
        key = f"class_{int(class_id)}"
        if key in lb:
            return f"{lb[key]} (class {class_id})"

    if label_mode == "binary-stress" or n_classes == 2:
        thr = 7
        if manifest and "label" in manifest:
            thr = manifest["label"].get("binary_threshold", thr)
        if class_id == 0:
            return f"Below threshold (class 0, stress score < {thr})"
        return f"Stressed / at or above threshold (class 1, stress score >= {thr})"

    if label_mode == "three-level" and n_classes == 3:
        scheme = three_level_scheme or "wide-normal"
        if scheme == "wide-normal":
            mapping = {
                0: "Relaxed / low (mapped from stress score ~1-3)",
                1: "Normal (mapped from stress score ~4-7)",
                2: "Stressed / high (mapped from stress score ~8-10)",
            }
        elif scheme == "classic":
            mapping = {
                0: "Relaxed / low (mapped from stress score ~1-4)",
                1: "Normal (mapped from stress score ~5-7)",
                2: "Stressed / high (mapped from stress score ~8-10)",
            }
        elif scheme == "early-stress":
            mapping = {
                0: "Relaxed / low (mapped from stress score ~1-4)",
                1: "Elevated (mapped from stress score ~5-6)",
                2: "Stressed / high (mapped from stress score ~7-10)",
            }
        else:
            mapping = {i: f"Level {i}" for i in range(3)}
        return mapping.get(class_id, f"class_{class_id}")

    if label_mode == "score-10":
        return f"Predicted stress score bin (class {class_id}, maps to score {class_id + 1})"

    return f"class_{class_id}"


def _prompt_float(name: str, hint: str) -> float:
    while True:
        raw = input(f"{name} ({hint}): ").strip()
        try:
            return float(raw)
        except ValueError:
            print("  Please enter a valid number.", file=sys.stderr)


def _prompt_int_choice(prompt: str, low: int, high: int) -> int:
    while True:
        raw = input(f"{prompt} [{low}-{high}]: ").strip()
        try:
            v = int(raw)
            if low <= v <= high:
                return v
        except ValueError:
            pass
        print(f"  Enter an integer between {low} and {high}.", file=sys.stderr)


VITAL_COLUMNS = ("Age", "Heart Rate", "Daily Steps", "Sleep Duration")

# Only used for legacy z-scored bundles when no scaling JSON is supplied. These are the real
# statistics of data/sleep_health_dataset.csv, recovered and verified against the z-scored CSV
# by prepare_dataset.py -- not the guesses that were here before.
_BUILTIN_RAW_SCALING: Dict[str, Tuple[float, float]] = {
    "Age": (48.394667, 18.154455),
    "Heart Rate": (74.763333, 12.233260),
    "Daily Steps": (6119.566000, 2822.060932),
    "Sleep Duration": (7.751733, 0.900461),
}

_RAW_HINTS = {
    "Age": "years",
    "Heart Rate": "resting bpm (approx.)",
    "Daily Steps": "steps per day",
    "Sleep Duration": "hours (use same unit as your scaling JSON)",
}


def load_base_column_names(csv_path: Path) -> List[str]:
    df = pd.read_csv(csv_path, nrows=0)
    cols = [c for c in df.columns if c != "Target_Stress_Level"]
    return cols


def load_vital_scaling_json(path: Path) -> Dict[str, Tuple[float, float]]:
    with path.open(encoding="utf-8") as fp:
        data = json.load(fp)
    out: Dict[str, Tuple[float, float]] = {}
    for col in VITAL_COLUMNS:
        if col not in data:
            raise ValueError(f"Scaling JSON missing key {col!r} (need all of {list(VITAL_COLUMNS)}).")
        entry = data[col]
        mean = float(entry["mean"])
        std = float(entry["std"])
        if std <= 1e-12:
            raise ValueError(f"{col}: std must be positive.")
        out[col] = (mean, std)
    return out


def resolve_vital_scaling(
    input_mode: str,
    scaling_json: Optional[Path],
    expects_raw: bool = False,
) -> Tuple[Dict[str, Tuple[float, float]], str]:
    """
    Returns (scaling_per_vital, source_description) for raw mode.
    For z mode returns empty dict and 'z_input'.
    """
    # A raw-units bundle takes physical values directly; scaling them would be the bug.
    if expects_raw:
        return {}, "raw_physical (no conversion)"
    if input_mode == "z":
        return {}, "z_input"

    default_path = Path(__file__).resolve().parent / "vital_scaling.json"
    if scaling_json is not None:
        if not scaling_json.is_file():
            print(f"Scaling file not found: {scaling_json}", file=sys.stderr)
            sys.exit(1)
        return load_vital_scaling_json(scaling_json), str(scaling_json)

    if default_path.is_file():
        return load_vital_scaling_json(default_path), str(default_path)

    return dict(_BUILTIN_RAW_SCALING), "built_in_defaults"


def collect_base_features(
    base_column_names: List[str],
    input_mode: str,
    vital_scaling: Dict[str, Tuple[float, float]],
    scaling_source: str,
    show_scaled: bool,
    expects_raw: bool = False,
) -> Dict[str, float]:
    row: Dict[str, float] = {c: 0.0 for c in base_column_names}

    if expects_raw:
        print(
            "\n--- Vitals (physical units) ---\n"
            "This bundle was trained on raw units, so values go to the model unchanged.\n"
            "Trained ranges: Age 18-80, Heart Rate 43-109 bpm, Daily Steps 1000-16036,\n"
            "Sleep Duration 5.1-10.0 hours. Outside those the trees extrapolate.\n"
        )
    elif input_mode == "z":
        print(
            "\n--- Vitals (z-scores, same as training CSV; roughly -3 to +3) ---\n"
            "Use 0 for about average vs the training distribution.\n"
        )
    else:
        print(
            "\n--- Vitals (simple / physical units) ---\n"
            f"Converted to z-scores for the model using: {scaling_source}\n"
        )

    for col in VITAL_COLUMNS:
        if col not in base_column_names:
            continue
        if expects_raw:
            row[col] = _prompt_float(col, _RAW_HINTS.get(col, "raw value"))
        elif input_mode == "z":
            row[col] = _prompt_float(col, "z-score")
        else:
            mean, std = vital_scaling[col]
            hint = _RAW_HINTS.get(col, "raw value")
            raw_val = _prompt_float(col, hint)
            z = (raw_val - mean) / std
            row[col] = z
            if show_scaled:
                print(f"  (model input z-score for {col}: {z:.4f}, mean={mean}, std={std})")

    print("\n--- Gender (Gender_Male) ---")
    g = _prompt_int_choice("0 = Female / other, 1 = Male", 0, 1)
    if "Gender_Male" in base_column_names:
        row["Gender_Male"] = float(g)

    occ_cols = [c for c in base_column_names if c.startswith("Occupation_")]
    if occ_cols:
        print("\n--- Occupation ---")
        labels = [c.replace("Occupation_", "") for c in occ_cols]
        for i, lab in enumerate(labels, start=1):
            print(f"  {i:2d}. {lab}")
        choice = _prompt_int_choice("Occupation number", 1, len(occ_cols))
        chosen = occ_cols[choice - 1]
        for c in occ_cols:
            row[c] = 1.0 if c == chosen else 0.0

    bmi_cols = [c for c in base_column_names if c.startswith("BMI Category_")]
    if bmi_cols:
        print("\n--- BMI category ---")
        print("  1. Normal (not obese, not overweight, not underweight)")
        print("  2. Overweight")
        print("  3. Obese")
        print("  4. Underweight")
        bmi_choice = _prompt_int_choice("BMI option", 1, 4)
        for c in bmi_cols:
            row[c] = 0.0
        if bmi_choice == 2 and "BMI Category_Overweight" in row:
            row["BMI Category_Overweight"] = 1.0
        elif bmi_choice == 3 and "BMI Category_Obese" in row:
            row["BMI Category_Obese"] = 1.0
        elif bmi_choice == 4 and "BMI Category_Underweight" in row:
            row["BMI Category_Underweight"] = 1.0

    return row


def build_dataframe(
    row: Dict[str, float],
    base_column_names: List[str],
    feature_mode: str,
    model: Any,
    column_order_fallback: List[str],
) -> pd.DataFrame:
    df = pd.DataFrame([{c: row[c] for c in base_column_names}])
    if feature_mode == "engineered":
        if add_engineered_features is None:
            raise RuntimeError("feature_mode=engineered requires optimize_ml_engine.add_engineered_features.")
        df = add_engineered_features(df)
    fin = getattr(model, "feature_names_in_", None)
    target_names = list(fin) if fin is not None else list(column_order_fallback)
    missing = [c for c in target_names if c not in df.columns]
    if missing:
        raise ValueError(f"Missing columns required by the model: {missing[:8]}...")
    return df[target_names].astype(np.float32)


def main() -> None:
    parser = argparse.ArgumentParser(description="Interactive StressGuard stress-level prediction")
    parser.add_argument(
        "--joblib-path",
        type=str,
        default=str(
            _here()
            / "mobile_export"
            / "binary_voting_top3_raw"
            / "voting_top3_sklearn.joblib"
        ),
        help="Path to voting_top3_sklearn.joblib or best_tuned_model.joblib",
    )
    parser.add_argument(
        "--manifest",
        type=str,
        default=str(
            _here()
            / "mobile_export"
            / "binary_voting_top3_raw"
            / "stressguard_mobile_manifest.json"
        ),
        help="Optional manifest JSON for friendly class names (default: alongside mobile export)",
    )
    parser.add_argument(
        "--input-mode",
        type=str,
        choices=("z", "raw"),
        default="raw",
        help="z = enter z-scores for vitals; raw = years, bpm, steps, sleep hours (converted via scaling JSON)",
    )
    parser.add_argument(
        "--scaling-json",
        type=str,
        default=None,
        help="Legacy z-scored bundles only: JSON with mean/std per vital. If omitted, uses "
        "vital_scaling.json beside this script if it exists, else the recovered constants. "
        "Ignored for bundles declaring input_units=raw_physical.",
    )
    parser.add_argument(
        "--show-scaled",
        action="store_true",
        help="With --input-mode raw, print z-score sent to the model for each vital.",
    )
    args = parser.parse_args()

    joblib_path = Path(args.joblib_path)
    if not joblib_path.is_file():
        print(f"Model file not found: {joblib_path}", file=sys.stderr)
        sys.exit(1)

    manifest: Optional[Dict[str, Any]] = None
    if args.manifest:
        mp = Path(args.manifest)
        if mp.is_file():
            manifest = load_manifest(mp)

    model, meta = load_model_bundle(joblib_path)
    feature_names = resolve_feature_order(model, meta)
    feature_mode = meta.get("feature_mode") or "base"
    label_mode = meta.get("label_mode") or (manifest or {}).get("label", {}).get("type", "three-level")
    scheme = meta.get("three_level_scheme")
    if scheme is None and manifest and "label" in manifest:
        scheme = manifest["label"].get("three_level_scheme")

    classes = getattr(model, "classes_", None)
    n_classes = int(len(classes)) if classes is not None else int(meta.get("n_classes") or 3)

    # The manifest states what the model expects. Follow it, rather than trusting --input-mode
    # to have been set correctly: a mismatch produces plausible numbers, not an error.
    input_units = (manifest or {}).get("input_units")
    expects_raw = bool(input_units and input_units.startswith("raw"))

    print("StressGuard - interactive predictor")
    print(f"Model: {joblib_path}")
    if manifest:
        print(f"Manifest: {args.manifest}")
    if expects_raw:
        print(f"Input units: {input_units} (entered values are sent unchanged)")
        if args.input_mode == "z":
            print(
                "NOTE: --input-mode z ignored. This bundle was trained on raw physical units.",
                file=sys.stderr,
            )
    else:
        print(f"Input mode: {args.input_mode}")

    scaling_path = Path(args.scaling_json) if args.scaling_json else None
    vital_scaling, scaling_src = resolve_vital_scaling(args.input_mode, scaling_path, expects_raw)
    if not expects_raw and args.input_mode == "raw" and scaling_src == "built_in_defaults":
        print(
            "NOTE: this bundle declares no input_units, so it predates the units fix and is "
            "assumed to expect z-scores. Converting with the statistics recovered from "
            "data/sleep_health_dataset.csv. Pass --scaling-json PATH to override.\n",
            file=sys.stderr,
        )

    if feature_mode == "engineered":
        csv_default = _here() / "data" / "StressGuard_Iteration1_Balanced_Data.csv"
        if not csv_default.is_file():
            print(
                f"feature_mode=engineered requires {csv_default} to read base column names.",
                file=sys.stderr,
            )
            sys.exit(1)
        base_column_names = load_base_column_names(csv_default)
    else:
        base_column_names = feature_names

    row = collect_base_features(
        base_column_names,
        args.input_mode,
        vital_scaling,
        scaling_src,
        args.show_scaled,
        expects_raw,
    )
    x = build_dataframe(row, base_column_names, feature_mode, model, feature_names)

    pred = int(model.predict(x.to_numpy(dtype=np.float32, copy=False))[0])
    proba = model.predict_proba(x.to_numpy(dtype=np.float32, copy=False))[0]

    name = class_display_name(pred, str(label_mode), scheme, int(n_classes), manifest)

    print("\n========== Result ==========")
    print(f"Predicted class: {pred}")
    print(f"Stress level: {name}")
    print("\nClass probabilities:")
    for i, p in enumerate(proba):
        label = class_display_name(i, str(label_mode), scheme, int(n_classes), manifest)
        print(f"  {i}: {float(p):.4f}  -  {label}")
    print("============================\n")


if __name__ == "__main__":
    main()

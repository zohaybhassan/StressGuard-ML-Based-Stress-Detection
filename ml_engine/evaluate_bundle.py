"""
Evaluate an exported StressGuard mobile bundle.

Does two things the tuning reports cannot:

  1. Reports per-class precision/recall/F1 for the ensemble that was *actually exported*.
     The tuning report's Voting_top3_tuned figure describes whichever three models won CV,
     which is not necessarily what got exported (see --ensemble-members there).

  2. Runs the .onnx graphs through onnxruntime and checks they agree with the sklearn
     estimator they came from. This is the Python side of the Python-vs-Android validation:
     if these disagree, the bundle is broken before Android is even involved.

Usage:
    python evaluate_bundle.py --bundle mobile_export/binary_voting_top3_raw
    python evaluate_bundle.py --bundle mobile_export/three_level_voting_wide_normal_raw
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Dict, List

import joblib
import numpy as np
import onnxruntime as ort
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix, f1_score
from sklearn.model_selection import train_test_split

from optimize_ml_engine import load_data

HERE = Path(__file__).resolve().parent


def resolve(value: str) -> Path:
    path = Path(value)
    return path if path.is_absolute() else HERE / path


def onnx_probabilities(session: ort.InferenceSession, x: np.ndarray, n_classes: int) -> np.ndarray:
    """
    Run one exported graph and return an (n_samples, n_classes) probability matrix.

    Output shape differs per exporter: skl2onnx wraps RandomForest's probabilities in a
    ZipMap (a list of class-index-to-probability dicts), while the onnxmltools converters
    emit a plain array.
    """
    input_name = session.get_inputs()[0].name
    names = [o.name for o in session.get_outputs()]
    prob_name = next((n for n in names if "probab" in n.lower()), names[-1])

    raw = session.run([prob_name], {input_name: x})[0]

    if isinstance(raw, list):  # ZipMap
        out = np.zeros((len(raw), n_classes), dtype=np.float64)
        for row, mapping in enumerate(raw):
            for key, value in mapping.items():
                out[row, int(key)] = value
        return out

    out = np.asarray(raw, dtype=np.float64)
    if out.ndim == 1:
        out = out.reshape(-1, n_classes)
    return out


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--bundle", type=str, required=True)
    parser.add_argument("--dataset", type=str, default=None,
                        help="Defaults to the dataset_path recorded in the bundle manifest")
    parser.add_argument("--test-size", type=float, default=0.2)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    bundle = resolve(args.bundle)
    manifest: Dict[str, Any] = json.loads(
        (bundle / "stressguard_mobile_manifest.json").read_text(encoding="utf-8")
    )
    label = manifest.get("label", {})
    label_mode = label.get("type", manifest.get("label_mode", "binary-stress"))
    scheme = label.get("three_level_scheme", "classic")
    threshold = int(label.get("binary_threshold", 7))
    feature_names: List[str] = manifest["feature_names"]

    n_classes = int(manifest.get("n_classes") or label.get("n_classes") or 0)
    class_names = [label.get(f"class_{i}", f"class_{i}") for i in range(n_classes)]

    dataset = resolve(args.dataset) if args.dataset else Path(manifest["dataset_path"])
    if not dataset.is_file():
        fallback = HERE / "data" / dataset.name
        if not fallback.is_file():
            raise FileNotFoundError(f"dataset not found: {dataset} (nor {fallback})")
        dataset = fallback

    print("=" * 78)
    print(f"Bundle    {bundle.name}")
    print(f"Model     {manifest.get('selected_model')}  ({manifest.get('export_kind')})")
    print(f"Members   {[m['tuned_name'] for m in manifest['ensemble']['members']]}")
    print(f"Labels    {label_mode}" + (f" / {scheme}" if label_mode == "three-level" else ""))
    print(f"Dataset   {dataset.name}")
    print("=" * 78)

    x, y = load_data(dataset, label_mode, scheme, threshold)
    if list(x.columns) != feature_names:
        raise AssertionError(
            "dataset columns do not match the manifest's feature_names; "
            f"first mismatch at {next(i for i, (a, b) in enumerate(zip(x.columns, feature_names)) if a != b)}"
        )

    _, x_test, _, y_test = train_test_split(
        x, y, test_size=args.test_size, stratify=y, random_state=args.seed
    )
    x_np = np.ascontiguousarray(x_test.to_numpy(dtype=np.float32))

    # --- sklearn side -------------------------------------------------------------
    payload = joblib.load(bundle / "voting_top3_sklearn.joblib")
    model = payload["model_onnx_aligned"]
    sk_proba = model.predict_proba(x_np)
    sk_pred = sk_proba.argmax(axis=1)

    print(f"\nHoldout: {len(y_test)} samples")
    print(f"  accuracy    {accuracy_score(y_test, sk_pred):.4f}")
    print(f"  weighted F1 {f1_score(y_test, sk_pred, average='weighted'):.4f}")

    print("\nPer-class (sklearn ensemble):")
    report = classification_report(
        y_test, sk_pred, target_names=class_names, output_dict=True, zero_division=0
    )
    print(f"  {'class':<22} {'precision':>9} {'recall':>8} {'f1':>8} {'support':>8}")
    for name in class_names:
        r = report[name]
        print(f"  {name:<22} {r['precision']:>9.4f} {r['recall']:>8.4f} "
              f"{r['f1-score']:>8.4f} {int(r['support']):>8d}")

    print("\nConfusion matrix (rows = actual, cols = predicted):")
    cm = confusion_matrix(y_test, sk_pred)
    print("     " + "".join(f"{n[:10]:>12}" for n in class_names))
    for i, row in enumerate(cm):
        print(f"  {class_names[i][:10]:<10}" + "".join(f"{v:>12}" for v in row))

    # The class that fires alerts: recall matters more than overall accuracy, because a
    # missed high-stress reading is the failure the user actually notices.
    alert_class = n_classes - 1
    alert_recall = report[class_names[alert_class]]["recall"]
    print(f"\n  ALERT CLASS '{class_names[alert_class]}' recall = {alert_recall:.4f} "
          f"({int(report[class_names[alert_class]]['support'] * alert_recall)} of "
          f"{int(report[class_names[alert_class]]['support'])} caught)")

    # --- ONNX side ----------------------------------------------------------------
    print("\nONNX graphs vs sklearn (equal-weight soft vote):")
    members = manifest["ensemble"]["members"]
    onnx_avg = np.zeros_like(sk_proba, dtype=np.float64)
    for member in members:
        path = bundle / member["onnx_file"]
        session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
        probs = onnx_probabilities(session, x_np, n_classes)
        onnx_avg += probs / len(members)
        print(f"  {member['tuned_name']:<22} {member['onnx_file']:<20} "
              f"rows={probs.shape[0]} classes={probs.shape[1]}")

    onnx_pred = onnx_avg.argmax(axis=1)
    max_delta = float(np.abs(onnx_avg - sk_proba).max())
    disagreements = int((onnx_pred != sk_pred).sum())

    print(f"\n  max |probability delta|  {max_delta:.3e}")
    print(f"  label disagreements      {disagreements} / {len(sk_pred)}")
    print(f"  ONNX accuracy            {accuracy_score(y_test, onnx_pred):.4f}")

    tolerance = 1e-5
    if max_delta > tolerance or disagreements > 0:
        print(f"\n  WARNING: ONNX and sklearn diverge beyond {tolerance:g}. The exported graphs "
              "do not faithfully reproduce the estimator; do not ship this bundle.")
    else:
        print(f"\n  OK: ONNX matches sklearn within {tolerance:g}. Safe to ship to Android.")

    print("=" * 78)


if __name__ == "__main__":
    main()

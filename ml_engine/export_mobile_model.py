"""
Export StressGuard Voting_top3_tuned models for on-device Android (Kotlin) via ONNX.

Supports:
  - Binary stress: default tuning report artifacts_tuned_binary (best acc/F1 ~0.894).
  - Three-level stress: e.g. artifacts_tuned_widenormal (wide-normal scheme, best ~0.814 acc).

Exports three ONNX base learners + stressguard_mobile_manifest.json.
Kotlin: average class probabilities (soft vote), argmax (binary: 2 probs; 3-class: 3 probs).

Requires: skl2onnx, onnx, onnxmltools (see requirements.txt).
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Dict, List, Tuple

import joblib
import numpy as np
import onnx
from catboost import CatBoostClassifier
from onnxmltools.convert import convert_lightgbm, convert_xgboost
from onnxmltools.convert.common.data_types import FloatTensorType
from sklearn.base import clone
from sklearn.ensemble import RandomForestClassifier, VotingClassifier
from sklearn.metrics import accuracy_score, f1_score
from sklearn.model_selection import train_test_split
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType as SklFloatTensorType
from xgboost import XGBClassifier

from optimize_ml_engine import add_engineered_features, load_data


def _onnx_io_summary(path: Path) -> Dict[str, Any]:
    model = onnx.load_model(str(path))
    return {
        "inputs": [(i.name, [d.dim_value for d in i.type.tensor_type.shape.dim]) for i in model.graph.input],
        "outputs": [(o.name, [d.dim_value for d in o.type.tensor_type.shape.dim]) for o in model.graph.output],
    }


def _build_estimators(report: Dict[str, Any], seed: int, n_classes: int) -> Dict[str, Any]:
    bp = report["best_params"]
    rf = RandomForestClassifier(random_state=seed, n_jobs=-1, **bp["RandomForest_tuned"])

    if n_classes <= 2:
        xgb = XGBClassifier(
            objective="binary:logistic",
            eval_metric="logloss",
            random_state=seed,
            n_jobs=-1,
            **bp["XGBoost_tuned"],
        )
        cat = CatBoostClassifier(
            loss_function="Logloss",
            random_seed=seed,
            verbose=False,
            **bp["CatBoost_tuned"],
        )
        lgbm_obj = "binary"
    else:
        xgb = XGBClassifier(
            objective="multi:softprob",
            eval_metric="mlogloss",
            random_state=seed,
            n_jobs=-1,
            **bp["XGBoost_tuned"],
        )
        cat = CatBoostClassifier(
            loss_function="MultiClass",
            random_seed=seed,
            verbose=False,
            **bp["CatBoost_tuned"],
        )
        lgbm_obj = "multiclass"

    out: Dict[str, Any] = {
        "RandomForest_tuned": rf,
        "XGBoost_tuned": xgb,
        "CatBoost_tuned": cat,
    }
    if "LightGBM_tuned" in bp:
        from lightgbm import LGBMClassifier

        out["LightGBM_tuned"] = LGBMClassifier(
            objective=lgbm_obj,
            random_state=seed,
            n_jobs=-1,
            verbose=-1,
            **bp["LightGBM_tuned"],
        )
    return out


def _export_one_sklearn_rf(rf: RandomForestClassifier, n_features: int, path: Path, opset: int) -> None:
    initial_types = [("float_input", SklFloatTensorType([None, n_features]))]
    onx = convert_sklearn(rf, initial_types=initial_types, target_opset=opset)
    path.write_bytes(onx.SerializeToString())


def _export_one_xgb(xgb: XGBClassifier, n_features: int, path: Path, opset: int) -> None:
    initial_types = [("input", FloatTensorType([None, n_features]))]
    booster = xgb.get_booster()
    onx = convert_xgboost(booster, initial_types=initial_types, target_opset=opset)
    path.write_bytes(onx.SerializeToString())


def _export_one_catboost(cat: CatBoostClassifier, path: Path, doc_hint: str) -> None:
    cat.save_model(
        str(path),
        format="onnx",
        export_parameters={
            "onnx_domain": "ai.catboost",
            "onnx_model_version": 1,
            "onnx_doc_string": doc_hint,
            "onnx_model_author": "StressGuard export_mobile_model.py",
        },
    )


def export_onnx_for_top_three(
    fitted_submodels: List[Tuple[str, Any]],
    n_features: int,
    out_dir: Path,
    opset: int,
    catboost_doc: str,
) -> List[Dict[str, Any]]:
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest_members: List[Dict[str, Any]] = []
    for full_name, est in fitted_submodels:
        if "RandomForest" in full_name:
            fname = "random_forest.onnx"
            _export_one_sklearn_rf(est, n_features, out_dir / fname, opset)
        elif "XGBoost" in full_name:
            fname = "xgboost.onnx"
            _export_one_xgb(est, n_features, out_dir / fname, opset)
        elif "CatBoost" in full_name:
            fname = "catboost.onnx"
            _export_one_catboost(est, out_dir / fname, catboost_doc)
        elif "LightGBM" in full_name:
            fname = "lightgbm.onnx"
            # skl2onnx has no shape calculator for LGBMClassifier; it needs onnxmltools,
            # the same way XGBoost does.
            initial_types = [("input", FloatTensorType([None, n_features]))]
            onx = convert_lightgbm(est, initial_types=initial_types, target_opset=opset)
            (out_dir / fname).write_bytes(onx.SerializeToString())
        else:
            raise ValueError(f"Unsupported estimator for ONNX export: {full_name}")

        p = out_dir / fname
        manifest_members.append(
            {
                "tuned_name": full_name,
                "onnx_file": fname,
                **_onnx_io_summary(p),
            }
        )
    return manifest_members


def _label_block(
    label_mode: str,
    scheme: str,
    binary_threshold: int,
    n_classes: int,
) -> Dict[str, Any]:
    if label_mode == "binary-stress":
        return {
            "type": label_mode,
            "binary_threshold": binary_threshold,
            "n_classes": 2,
            "class_0": "not_stressed",
            "class_1": "stressed",
        }
    if label_mode == "three-level":
        return {
            "type": label_mode,
            "three_level_scheme": scheme,
            "n_classes": n_classes,
            "class_0": "relaxed_low_stress",
            "class_1": "normal",
            "class_2": "stressed_high",
            "scheme_note_wide_normal": (
                "wide-normal bins on 1–10 score: classes 0≈1–3, 1≈4–7, 2≈8–10 (see optimize_ml_engine.map_to_three_level_stress)."
            ),
        }
    return {"type": label_mode, "n_classes": n_classes}


def _resolve_members(
    requested: str,
    report: Dict[str, Any],
    built: Dict[str, Any],
) -> List[str]:
    """
    Decide which base learners go into the exported soft-voting ensemble.

    The tuning report's top_three_for_ensembles is whichever three scored best in CV, which
    varies with the data and can include SVM or LightGBM. Those are deliberately not the
    default here: the Android bundle, its manifest and the project write-up are all built
    around three tree models that convert cleanly to ONNX, and their accuracy is within a
    fraction of a point of the CV leaders. Pass --ensemble-members to override.
    """
    names = [n.strip() for n in requested.split(",") if n.strip()]
    if not names:
        raise ValueError("--ensemble-members is empty")

    resolved: List[str] = []
    for name in names:
        key = name if name.endswith("_tuned") else f"{name}_tuned"
        if key not in built:
            raise KeyError(
                f"cannot build {key!r} for export. Available: {sorted(built)}. "
                "Tuned parameters for it may be absent from the report, or the exporter has "
                "no ONNX conversion path for that model type."
            )
        resolved.append(key)

    cv_top = report.get("top_three_for_ensembles", [])
    if cv_top and list(cv_top) != resolved:
        print(
            f"NOTE: exporting {resolved} instead of the report's CV top three {list(cv_top)}. "
            "Metrics recorded in the manifest are measured on the exported ensemble, not on "
            "the report's Voting_top3_tuned figure."
        )
    return resolved


def _resolve_dataset(override: str | None, recorded: str | None, root: Path) -> Path:
    """
    Locate the training CSV.

    Tuning reports record an absolute dataset_path from whichever machine ran the tuning
    (e.g. D:\\FYP\\...), so that path is usually dead on any other checkout. Prefer an
    explicit --dataset, then the recorded path if it still resolves, then this script's
    own data/ directory.
    """
    if override:
        path = Path(override)
        if not path.is_file():
            raise FileNotFoundError(f"--dataset not found: {path}")
        return path

    # Prefer this checkout's own data/ over the recorded absolute path. The recorded path may
    # still resolve on the original machine (e.g. D:\FYP\...) while pointing at a copy that
    # is not the one under version control here, which would silently train on other data.
    candidates: List[Path] = []
    if recorded:
        candidates.append(root / "data" / Path(recorded).name)
    candidates.append(root / "data" / "StressGuard_Iteration1_Balanced_Data.csv")
    if recorded:
        candidates.append(Path(recorded))

    for candidate in candidates:
        if candidate.is_file():
            if recorded and candidate != Path(recorded):
                print(f"NOTE: ignoring recorded dataset_path {recorded!r}; using {candidate}")
            return candidate

    tried = "\n  ".join(str(c) for c in candidates)
    raise FileNotFoundError(
        f"Could not locate the training CSV. Tried:\n  {tried}\nPass --dataset PATH explicitly."
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Export StressGuard Voting_top3 for Android ONNX")
    parser.add_argument(
        "--tuning-report",
        type=str,
        default=None,
        help="Path to tuning_report.json (defaults by --export-kind)",
    )
    parser.add_argument(
        "--export-kind",
        type=str,
        choices=["binary", "three_level_wide_normal"],
        default="binary",
        help="Preset: which tuned run to export",
    )
    parser.add_argument(
        "--dataset",
        type=str,
        default=None,
        help="Training CSV. Overrides the dataset_path recorded in the tuning report, which "
        "may point at a stale absolute path from the machine that ran the tuning.",
    )
    parser.add_argument(
        "--ensemble-members",
        type=str,
        default="RandomForest,XGBoost,CatBoost",
        help="Comma-separated base learners for the exported soft vote. Defaults to the three "
        "tree models the Android bundle is built around, rather than the tuning report's CV "
        "top three, which may include models with no ONNX conversion path here.",
    )
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--test-size", type=float, default=0.2)
    parser.add_argument("--opset", type=int, default=15)
    parser.add_argument("--output-dir", type=str, default=None)
    args = parser.parse_args()

    root = Path(__file__).resolve().parent
    if args.export_kind == "binary":
        report_path = Path(
            args.tuning_report
            or (root / "artifacts_tuned_binary" / "tuning_report.json")
        )
        out_dir = Path(args.output_dir or (root / "mobile_export" / "binary_voting_top3"))
        rationale = (
            "Voting_top3_tuned binary stress on StressGuard_Iteration1_Balanced_Data.csv (base features). "
            "Reference test_accuracy ~0.894, test_f1_weighted ~0.894."
        )
        cat_doc = "StressGuard binary CatBoost base learner"
    else:
        report_path = Path(
            args.tuning_report
            or (root / "artifacts_tuned_widenormal" / "tuning_report.json")
        )
        out_dir = Path(args.output_dir or (root / "mobile_export" / "three_level_voting_wide_normal"))
        rationale = (
            "Voting_top3_tuned three-level (wide-normal) on StressGuard_Iteration1_Balanced_Data.csv (base features). "
            "Reference test_accuracy ~0.814, test_f1_weighted ~0.814."
        )
        cat_doc = "StressGuard 3-class CatBoost base learner (MultiClass)"

    if not report_path.is_file():
        raise FileNotFoundError(
            f"Missing {report_path}. Run optimize_ml_engine.py with matching label/scheme and --output-dir, "
            "or pass --tuning-report explicitly."
        )

    report = json.loads(report_path.read_text(encoding="utf-8"))
    data_path = _resolve_dataset(args.dataset, report.get("dataset_path"), root)
    label_mode = report["label_mode"]
    scheme = report.get("three_level_scheme", "classic")
    binary_threshold = int(report.get("binary_threshold", 7))
    feature_mode = report.get("feature_mode", "base")

    x, y = load_data(data_path, label_mode, scheme, binary_threshold)
    if feature_mode == "engineered":
        x = add_engineered_features(x)

    n_classes = int(y.nunique())
    feature_names = list(x.columns)
    n_features = len(feature_names)
    x_train, x_test, y_train, y_test = train_test_split(
        x, y, test_size=args.test_size, stratify=y, random_state=args.seed
    )

    built = _build_estimators(report, args.seed, n_classes)
    top_three: List[str] = _resolve_members(args.ensemble_members, report, built)
    estimators_list: List[Tuple[str, Any]] = [
        (full.replace("_tuned", ""), built[full]) for full in top_three
    ]

    voting = VotingClassifier(estimators=estimators_list, voting="soft", n_jobs=-1)
    voting.fit(x_train, y_train)
    y_pred = voting.predict(x_test)
    acc = float(accuracy_score(y_test, y_pred))
    f1 = float(f1_score(y_test, y_pred, average="weighted"))

    X_train_np = np.ascontiguousarray(x_train.to_numpy(dtype=np.float32))
    X_test_np = np.ascontiguousarray(x_test.to_numpy(dtype=np.float32))
    export_models: Dict[str, Any] = {full: clone(built[full]) for full in top_three}
    export_estimator_list = [(full.replace("_tuned", ""), export_models[full]) for full in top_three]
    voting_export = VotingClassifier(estimators=export_estimator_list, voting="soft", n_jobs=-1)
    voting_export.fit(X_train_np, y_train)
    y_pred_np = voting_export.predict(X_test_np)
    acc_np = float(accuracy_score(y_test, y_pred_np))
    f1_np = float(f1_score(y_test, y_pred_np, average="weighted"))

    out_dir = Path(args.output_dir) if args.output_dir else out_dir
    out_dir.mkdir(parents=True, exist_ok=True)

    extra_meta: Dict[str, Any] = {
        "feature_names": feature_names,
        "feature_mode": feature_mode,
        "label_mode": label_mode,
        "three_level_scheme": scheme if label_mode == "three-level" else None,
        "binary_threshold": binary_threshold if label_mode == "binary-stress" else None,
        "n_classes": n_classes,
    }

    joblib.dump(
        {
            "model": voting,
            "model_onnx_aligned": voting_export,
            **extra_meta,
        },
        out_dir / "voting_top3_sklearn.joblib",
    )

    fitted_submodels: List[Tuple[str, Any]] = [
        (full, voting_export.named_estimators_[full.replace("_tuned", "")]) for full in top_three
    ]

    members = export_onnx_for_top_three(
        fitted_submodels, n_features, out_dir, args.opset, cat_doc
    )

    manifest: Dict[str, Any] = {
        "selected_model": "Voting_top3_tuned",
        "export_kind": args.export_kind,
        # Describe what was actually exported. A hardcoded rationale goes stale the first time
        # the data or the ensemble membership changes, and this string ends up in the report.
        "rationale": (
            f"Soft-voting ensemble of {[m['tuned_name'] for m in members]} for {label_mode}"
            + (f" ({scheme})" if label_mode == "three-level" else "")
            + f", trained on {data_path.name} ({feature_mode} features, raw physical units). "
            f"Holdout test_accuracy {acc_np:.4f}, test_f1_weighted {f1_np:.4f} "
            f"over {len(y_test)} samples."
        ),
        "export_preset": rationale,
        "tuning_report_path": str(report_path),
        "dataset_path": str(data_path),
        "feature_mode": feature_mode,
        "label": _label_block(label_mode, scheme, binary_threshold, n_classes),
        "reported_voting_metrics": report["final_results"]["Voting_top3_tuned"],
        "refit_holdout_metrics_dataframe_fit": {"test_accuracy": acc, "test_f1_weighted": f1},
        "onnx_bundle_holdout_metrics_numpy_refit": {"test_accuracy": acc_np, "test_f1_weighted": f1_np},
        "onnx_export_note": (
            "Sub-models were refit on float32 NumPy (same column order as feature_names) so XGBoost "
            "exports to ONNX; use the same input layout on Android."
        ),
        "kotlin_inference": (
            "For each ONNX, obtain a length-K probability vector (K=n_classes). "
            "Average the three vectors element-wise, then argmax for the predicted class. "
            "If a model returns logits, apply softmax per model before averaging."
        ),
        "feature_names": feature_names,
        "n_features": n_features,
        "n_classes": n_classes,
        # The contract the caller must honour. "raw_physical" means Age in years, Heart Rate in
        # bpm, Daily Steps as a count and Sleep Duration in hours, passed through unscaled.
        # Recorded explicitly because sending standardized values to a model trained on raw
        # units (or the reverse) produces confident, constant, wrong predictions rather than
        # any visible error. See diagnose_input_scale.py.
        "input_units": "raw_physical",
        "ensemble": {
            "type": "soft_vote_equal_weights",
            "members": members,
        },
    }
    (out_dir / "stressguard_mobile_manifest.json").write_text(
        json.dumps(manifest, indent=2), encoding="utf-8"
    )

    print(
        json.dumps(
            {
                "output_dir": str(out_dir),
                "export_kind": args.export_kind,
                "n_classes": n_classes,
                "dataframe_fit_metrics": manifest["refit_holdout_metrics_dataframe_fit"],
                "onnx_numpy_refit_metrics": manifest["onnx_bundle_holdout_metrics_numpy_refit"],
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()

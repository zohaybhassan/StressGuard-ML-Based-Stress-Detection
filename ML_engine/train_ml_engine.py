import argparse
import json
import tempfile
from pathlib import Path
from typing import Dict, Tuple

import joblib
import mlflow
import mlflow.sklearn
import numpy as np
import pandas as pd
from catboost import CatBoostClassifier
from lightgbm import LGBMClassifier
from sklearn.ensemble import RandomForestClassifier, StackingClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    accuracy_score,
    classification_report,
    confusion_matrix,
    f1_score,
)
from sklearn.model_selection import StratifiedKFold, cross_val_score, train_test_split
from sklearn.neural_network import MLPClassifier
from xgboost import XGBClassifier


def map_to_three_level_stress(y: pd.Series) -> pd.Series:
    """
    Maps numeric stress scores to 3 classes:
    - 0: Relaxed (1-4)
    - 1: Normal (5-7)
    - 2: Stressed (8-10)
    """
    bins = [0, 4, 7, 10]
    labels = [0, 1, 2]
    return pd.cut(y, bins=bins, labels=labels, include_lowest=True).astype(int)


def load_data(csv_path: Path, label_mode: str) -> Tuple[pd.DataFrame, pd.Series]:
    df = pd.read_csv(csv_path)
    if "Target_Stress_Level" not in df.columns:
        raise ValueError("Expected 'Target_Stress_Level' column in dataset.")

    x = df.drop(columns=["Target_Stress_Level"])
    y_raw = df["Target_Stress_Level"].astype(int)

    if label_mode == "three-level":
        y = map_to_three_level_stress(y_raw)
    else:
        # XGBoost multiclass expects zero-based class labels.
        y = y_raw - 1

    return x, y


def build_models(seed: int) -> Dict[str, object]:
    base_rf = RandomForestClassifier(
        n_estimators=300,
        max_depth=None,
        min_samples_leaf=2,
        random_state=seed,
        n_jobs=-1,
    )

    base_xgb = XGBClassifier(
        n_estimators=300,
        learning_rate=0.05,
        max_depth=5,
        subsample=0.9,
        colsample_bytree=0.9,
        objective="multi:softprob",
        eval_metric="mlogloss",
        random_state=seed,
        n_jobs=-1,
    )

    base_lgbm = LGBMClassifier(
        n_estimators=300,
        learning_rate=0.05,
        num_leaves=31,
        max_depth=-1,
        subsample=0.9,
        colsample_bytree=0.9,
        objective="multiclass",
        random_state=seed,
        n_jobs=-1,
        verbose=-1,
    )

    base_cat = CatBoostClassifier(
        iterations=300,
        learning_rate=0.05,
        depth=6,
        loss_function="MultiClass",
        eval_metric="TotalF1",
        random_seed=seed,
        verbose=False,
    )

    meta_ann = MLPClassifier(
        hidden_layer_sizes=(64, 32),
        activation="relu",
        solver="adam",
        learning_rate_init=0.001,
        max_iter=800,
        random_state=seed,
    )

    stacking_engine = StackingClassifier(
        estimators=[("rf", base_rf), ("xgb", base_xgb)],
        final_estimator=meta_ann,
        stack_method="predict_proba",
        cv=5,
        n_jobs=-1,
    )

    return {
        "RandomForest": base_rf,
        "XGBoost": base_xgb,
        "LightGBM": base_lgbm,
        "CatBoost": base_cat,
        "LogisticRegression": LogisticRegression(max_iter=2000, random_state=seed),
        "Stacking_RF_XGB_ANN": stacking_engine,
    }


def evaluate_models(
    models: Dict[str, object], x_train: pd.DataFrame, y_train: pd.Series, seed: int
) -> Dict[str, Dict[str, float]]:
    cv = StratifiedKFold(n_splits=5, shuffle=True, random_state=seed)
    scores = {}
    for name, model in models.items():
        cv_acc = cross_val_score(model, x_train, y_train, cv=cv, scoring="accuracy")
        cv_f1 = cross_val_score(model, x_train, y_train, cv=cv, scoring="f1_weighted")
        scores[name] = {
            "cv_accuracy_mean": float(np.mean(cv_acc)),
            "cv_accuracy_std": float(np.std(cv_acc)),
            "cv_f1_weighted_mean": float(np.mean(cv_f1)),
            "cv_f1_weighted_std": float(np.std(cv_f1)),
        }
    return scores


def train_and_test(
    model_name: str,
    model: object,
    x_train: pd.DataFrame,
    y_train: pd.Series,
    x_test: pd.DataFrame,
    y_test: pd.Series,
) -> Dict[str, object]:
    model.fit(x_train, y_train)
    y_pred = model.predict(x_test)

    results = {
        "model_name": model_name,
        "test_accuracy": float(accuracy_score(y_test, y_pred)),
        "test_f1_weighted": float(f1_score(y_test, y_pred, average="weighted")),
        "classification_report": classification_report(y_test, y_pred, output_dict=True),
        "confusion_matrix": confusion_matrix(y_test, y_pred).tolist(),
    }
    return results


def main() -> None:
    parser = argparse.ArgumentParser(description="StressGuard Iteration 2 ML Engine Trainer")
    parser.add_argument(
        "--data-path",
        type=str,
        default=str(Path(__file__).resolve().parents[1] / "StressGuard_Iteration1_Balanced_Data.csv"),
        help="Path to balanced dataset CSV",
    )
    parser.add_argument(
        "--label-mode",
        type=str,
        choices=["score-10", "three-level"],
        default="three-level",
        help="Use full 1-10 stress score or mapped 3-level classes",
    )
    parser.add_argument("--test-size", type=float, default=0.2, help="Holdout test size")
    parser.add_argument("--seed", type=int, default=42, help="Random seed")
    parser.add_argument(
        "--output-dir",
        type=str,
        default=str(Path(__file__).resolve().parent / "artifacts"),
        help="Directory to save trained model and reports",
    )
    parser.add_argument(
        "--mlflow-experiment",
        type=str,
        default="StressGuard-Iteration2",
        help="MLflow experiment name",
    )
    parser.add_argument(
        "--mlflow-tracking-uri",
        type=str,
        default=str(Path(__file__).resolve().parent / "mlruns"),
        help="MLflow tracking URI (local folder or server URI)",
    )
    args = parser.parse_args()

    data_path = Path(args.data_path)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    x, y = load_data(data_path, args.label_mode)
    x_train, x_test, y_train, y_test = train_test_split(
        x, y, test_size=args.test_size, stratify=y, random_state=args.seed
    )

    models = build_models(args.seed)
    cv_results = evaluate_models(models, x_train, y_train, args.seed)

    tracking_uri = args.mlflow_tracking_uri
    if "://" not in tracking_uri:
        tracking_uri = Path(tracking_uri).resolve().as_uri()
    mlflow.set_tracking_uri(tracking_uri)
    mlflow.set_experiment(args.mlflow_experiment)

    all_test_results: Dict[str, Dict[str, object]] = {}

    for model_name, model in models.items():
        with mlflow.start_run(run_name=f"{model_name}_{args.label_mode}") as run:
            model_cv = cv_results[model_name]
            model_test = train_and_test(
                model_name, model, x_train, y_train, x_test, y_test
            )
            all_test_results[model_name] = model_test

            mlflow.log_params(
                {
                    "model_name": model_name,
                    "label_mode": args.label_mode,
                    "seed": args.seed,
                    "test_size": args.test_size,
                    "train_samples": len(x_train),
                    "test_samples": len(x_test),
                    "feature_count": x.shape[1],
                    "tracking_dataset": str(data_path),
                }
            )
            mlflow.log_metrics(
                {
                    "cv_accuracy_mean": model_cv["cv_accuracy_mean"],
                    "cv_accuracy_std": model_cv["cv_accuracy_std"],
                    "cv_f1_weighted_mean": model_cv["cv_f1_weighted_mean"],
                    "cv_f1_weighted_std": model_cv["cv_f1_weighted_std"],
                    "test_accuracy": model_test["test_accuracy"],
                    "test_f1_weighted": model_test["test_f1_weighted"],
                }
            )

            with tempfile.TemporaryDirectory() as tmp_dir:
                tmp_path = Path(tmp_dir)
                report_path = tmp_path / "classification_report.json"
                cm_path = tmp_path / "confusion_matrix.json"
                report_path.write_text(
                    json.dumps(model_test["classification_report"], indent=2),
                    encoding="utf-8",
                )
                cm_path.write_text(
                    json.dumps(model_test["confusion_matrix"], indent=2), encoding="utf-8"
                )
                mlflow.log_artifact(str(report_path), artifact_path="evaluation")
                mlflow.log_artifact(str(cm_path), artifact_path="evaluation")

            mlflow.sklearn.log_model(
                sk_model=model,
                artifact_path="model",
                registered_model_name=None,
            )
            mlflow.set_tag("stressguard_iteration", "2")
            mlflow.set_tag("ml_engine_type", "tabular_classification")
            mlflow.set_tag("run_id", run.info.run_id)

    best_model_name = max(cv_results, key=lambda n: cv_results[n]["cv_f1_weighted_mean"])
    best_model = models[best_model_name]
    test_results = all_test_results[best_model_name]

    model_output_path = output_dir / "best_model.joblib"
    report_output_path = output_dir / "training_report.json"
    summary_output_path = output_dir / "training_summary.txt"

    joblib.dump(best_model, model_output_path)

    payload = {
        "label_mode": args.label_mode,
        "dataset_path": str(data_path),
        "mlflow_experiment": args.mlflow_experiment,
        "mlflow_tracking_uri": tracking_uri,
        "train_size": int(len(x_train)),
        "test_size": int(len(x_test)),
        "cv_results": cv_results,
        "all_model_test_results": all_test_results,
        "selected_model": best_model_name,
        "test_results": test_results,
        "feature_columns": list(x.columns),
    }

    with report_output_path.open("w", encoding="utf-8") as fp:
        json.dump(payload, fp, indent=2)

    summary_lines = [
        "StressGuard Iteration 2 Training Summary",
        f"Label mode: {args.label_mode}",
        f"Train samples: {len(x_train)} | Test samples: {len(x_test)}",
        "",
        "Cross-validation (weighted F1):",
    ]

    for name, metrics in sorted(
        cv_results.items(), key=lambda kv: kv[1]["cv_f1_weighted_mean"], reverse=True
    ):
        summary_lines.append(
            f"- {name}: {metrics['cv_f1_weighted_mean']:.4f} (+/- {metrics['cv_f1_weighted_std']:.4f})"
        )

    summary_lines.extend(
        [
            "",
            f"Selected model: {best_model_name}",
            f"Test Accuracy: {test_results['test_accuracy']:.4f}",
            f"Test Weighted F1: {test_results['test_f1_weighted']:.4f}",
            "",
            f"MLflow Experiment: {args.mlflow_experiment}",
            f"MLflow Tracking URI: {tracking_uri}",
            f"Saved model: {model_output_path}",
            f"Saved report: {report_output_path}",
        ]
    )

    summary_output_path.write_text("\n".join(summary_lines), encoding="utf-8")
    print("\n".join(summary_lines))


if __name__ == "__main__":
    main()

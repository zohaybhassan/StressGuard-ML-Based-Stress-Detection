import argparse
import json
import tempfile
from pathlib import Path
from typing import Dict, Tuple

import mlflow
import mlflow.sklearn
import numpy as np
import pandas as pd
from catboost import CatBoostClassifier
from lightgbm import LGBMClassifier
from sklearn.ensemble import RandomForestClassifier, StackingClassifier, VotingClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    accuracy_score,
    classification_report,
    confusion_matrix,
    f1_score,
)
from sklearn.model_selection import RandomizedSearchCV, StratifiedKFold, train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler
from sklearn.svm import SVC
from xgboost import XGBClassifier


def map_to_three_level_stress(y: pd.Series, scheme: str) -> pd.Series:
    if scheme == "classic":
        bins = [0, 4, 7, 10]  # 1-4, 5-7, 8-10
    elif scheme == "wide-normal":
        bins = [0, 3, 7, 10]  # 1-3, 4-7, 8-10
    elif scheme == "early-stress":
        bins = [0, 4, 6, 10]  # 1-4, 5-6, 7-10
    else:
        raise ValueError(f"Unsupported three-level scheme: {scheme}")
    labels = [0, 1, 2]
    return pd.cut(y, bins=bins, labels=labels, include_lowest=True).astype(int)


def load_data(
    csv_path: Path, label_mode: str, three_level_scheme: str, binary_threshold: int
) -> Tuple[pd.DataFrame, pd.Series]:
    df = pd.read_csv(csv_path)
    x = df.drop(columns=["Target_Stress_Level"])
    y_raw = df["Target_Stress_Level"].astype(int)
    if label_mode == "three-level":
        y = map_to_three_level_stress(y_raw, three_level_scheme)
    elif label_mode == "score-10":
        y = y_raw - 1
    else:
        y = (y_raw >= binary_threshold).astype(int)
    return x, y


def add_engineered_features(x: pd.DataFrame) -> pd.DataFrame:
    """
    Adds lightweight physiological interaction features.
    These are derived from existing normalized signals and are safe for fast experiments.
    """
    features = x.copy()
    eps = 1e-6

    if {"Heart Rate", "Daily Steps"}.issubset(features.columns):
        features["HR_to_Steps"] = features["Heart Rate"] / (np.abs(features["Daily Steps"]) + 1.0 + eps)
        features["HR_x_Steps"] = features["Heart Rate"] * features["Daily Steps"]

    if {"Heart Rate", "Sleep Duration"}.issubset(features.columns):
        features["HR_x_Sleep"] = features["Heart Rate"] * features["Sleep Duration"]
        features["HR_minus_Sleep"] = features["Heart Rate"] - features["Sleep Duration"]

    if {"Daily Steps", "Sleep Duration"}.issubset(features.columns):
        features["Steps_per_Sleep"] = features["Daily Steps"] / (np.abs(features["Sleep Duration"]) + 1.0 + eps)
        features["Steps_x_Sleep"] = features["Daily Steps"] * features["Sleep Duration"]

    if "Age" in features.columns and "Heart Rate" in features.columns:
        features["Age_x_HR"] = features["Age"] * features["Heart Rate"]

    bmi_cols = [c for c in features.columns if c.startswith("BMI Category_")]
    if bmi_cols:
        features["BMI_risk_sum"] = features[bmi_cols].sum(axis=1)

    occ_cols = [c for c in features.columns if c.startswith("Occupation_")]
    if occ_cols:
        features["Occupation_onehot_sum"] = features[occ_cols].sum(axis=1)

    if {"Gender_Male", "Heart Rate"}.issubset(features.columns):
        features["Male_x_HR"] = features["Gender_Male"] * features["Heart Rate"]

    if {"Gender_Male", "Daily Steps"}.issubset(features.columns):
        features["Male_x_Steps"] = features["Gender_Male"] * features["Daily Steps"]

    if {"Heart Rate", "Daily Steps", "Sleep Duration"}.issubset(features.columns):
        features["vital_abs_sum"] = (
            np.abs(features["Heart Rate"])
            + np.abs(features["Daily Steps"])
            + np.abs(features["Sleep Duration"])
        )
        features["HR_share_of_vitals"] = features["Heart Rate"] / (features["vital_abs_sum"] + eps)

    if {"Sleep Duration", "Heart Rate"}.issubset(features.columns):
        features["Sleep_minus_HR"] = features["Sleep Duration"] - features["Heart Rate"]

    if "Age" in features.columns:
        features["Age_sq"] = features["Age"] ** 2
    if "Heart Rate" in features.columns:
        features["HR_sq"] = features["Heart Rate"] ** 2
    if "Daily Steps" in features.columns:
        features["Steps_sq"] = features["Daily Steps"] ** 2

    return features


def evaluate_model(model: object, x_train: pd.DataFrame, y_train: pd.Series, x_test: pd.DataFrame, y_test: pd.Series) -> Dict[str, object]:
    model.fit(x_train, y_train)
    y_pred = model.predict(x_test)
    return {
        "test_accuracy": float(accuracy_score(y_test, y_pred)),
        "test_f1_weighted": float(f1_score(y_test, y_pred, average="weighted")),
        "classification_report": classification_report(y_test, y_pred, output_dict=True),
        "confusion_matrix": confusion_matrix(y_test, y_pred).tolist(),
    }


def tune_models(
    x_train: pd.DataFrame,
    y_train: pd.Series,
    seed: int,
    n_iter: int,
    cv_splits: int,
) -> Dict[str, object]:
    cv = StratifiedKFold(n_splits=cv_splits, shuffle=True, random_state=seed)
    n_classes = int(y_train.nunique())
    is_binary = n_classes == 2

    searches = {
        "RandomForest_tuned": RandomizedSearchCV(
            estimator=RandomForestClassifier(random_state=seed, n_jobs=-1),
            param_distributions={
                "n_estimators": [200, 300, 400, 500],
                "max_depth": [None, 8, 12, 16, 24],
                "min_samples_split": [2, 4, 8, 12],
                "min_samples_leaf": [1, 2, 4, 6],
                "max_features": ["sqrt", "log2", None],
            },
            n_iter=n_iter,
            scoring="f1_weighted",
            cv=cv,
            n_jobs=-1,
            random_state=seed,
            verbose=0,
        ),
        "XGBoost_tuned": RandomizedSearchCV(
            estimator=XGBClassifier(
                objective="binary:logistic" if is_binary else "multi:softprob",
                eval_metric="logloss" if is_binary else "mlogloss",
                random_state=seed,
                n_jobs=-1,
            ),
            param_distributions={
                "n_estimators": [200, 300, 400, 500],
                "learning_rate": [0.01, 0.03, 0.05, 0.07, 0.1],
                "max_depth": [3, 4, 5, 6, 8],
                "subsample": [0.7, 0.8, 0.9, 1.0],
                "colsample_bytree": [0.7, 0.8, 0.9, 1.0],
                "min_child_weight": [1, 3, 5],
                "gamma": [0, 0.1, 0.3],
            },
            n_iter=n_iter,
            scoring="f1_weighted",
            cv=cv,
            n_jobs=-1,
            random_state=seed,
            verbose=0,
        ),
        "LightGBM_tuned": RandomizedSearchCV(
            estimator=LGBMClassifier(
                objective="binary" if is_binary else "multiclass",
                random_state=seed,
                n_jobs=-1,
                verbose=-1,
            ),
            param_distributions={
                "n_estimators": [200, 300, 400, 500],
                "learning_rate": [0.01, 0.03, 0.05, 0.07, 0.1],
                "num_leaves": [15, 31, 63, 127],
                "max_depth": [-1, 6, 8, 10, 12],
                "subsample": [0.7, 0.8, 0.9, 1.0],
                "colsample_bytree": [0.7, 0.8, 0.9, 1.0],
                "min_child_samples": [10, 20, 30, 50],
            },
            n_iter=n_iter,
            scoring="f1_weighted",
            cv=cv,
            n_jobs=-1,
            random_state=seed,
            verbose=0,
        ),
        "CatBoost_tuned": RandomizedSearchCV(
            estimator=CatBoostClassifier(
                loss_function="Logloss" if is_binary else "MultiClass",
                random_seed=seed,
                verbose=False,
            ),
            param_distributions={
                "iterations": [200, 300, 400, 500],
                "learning_rate": [0.01, 0.03, 0.05, 0.07, 0.1],
                "depth": [4, 5, 6, 8, 10],
                "l2_leaf_reg": [1, 3, 5, 7, 9],
            },
            n_iter=n_iter,
            scoring="f1_weighted",
            cv=cv,
            n_jobs=-1,
            random_state=seed,
            verbose=0,
        ),
        "SVM_tuned": RandomizedSearchCV(
            estimator=Pipeline(
                [
                    ("scaler", StandardScaler()),
                    (
                        "svc",
                        SVC(
                            probability=True,
                            random_state=seed,
                            cache_size=500,
                        ),
                    ),
                ]
            ),
            param_distributions={
                "svc__C": [0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0],
                "svc__kernel": ["rbf", "linear"],
                "svc__gamma": ["scale", "auto", 1e-3, 1e-2, 0.05, 0.1],
            },
            n_iter=n_iter,
            scoring="f1_weighted",
            cv=cv,
            n_jobs=-1,
            random_state=seed,
            verbose=0,
        ),
    }

    tuned_estimators: Dict[str, object] = {}
    cv_scores: Dict[str, float] = {}
    best_params: Dict[str, Dict[str, object]] = {}
    for name, search in searches.items():
        search.fit(x_train, y_train)
        tuned_estimators[name] = search.best_estimator_
        cv_scores[name] = float(search.best_score_)
        best_params[name] = search.best_params_

    return {
        "estimators": tuned_estimators,
        "cv_scores": cv_scores,
        "best_params": best_params,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="StressGuard tuned model optimization run")
    parser.add_argument(
        "--data-path",
        type=str,
        default=str(Path(__file__).resolve().parents[1] / "StressGuard_Iteration1_Balanced_Data.csv"),
    )
    parser.add_argument(
        "--label-mode",
        type=str,
        choices=["three-level", "score-10", "binary-stress"],
        default="three-level",
    )
    parser.add_argument(
        "--three-level-scheme",
        type=str,
        choices=["classic", "wide-normal", "early-stress"],
        default="classic",
        help="Boundary scheme for three-level mapping",
    )
    parser.add_argument(
        "--binary-threshold",
        type=int,
        default=7,
        help="For binary-stress mode: stress=1 if Target_Stress_Level >= threshold",
    )
    parser.add_argument("--test-size", type=float, default=0.2)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--cv-splits", type=int, default=5)
    parser.add_argument("--n-iter", type=int, default=20)
    parser.add_argument(
        "--feature-mode",
        type=str,
        choices=["base", "engineered"],
        default="engineered",
        help="Use original features or add engineered interaction features",
    )
    parser.add_argument(
        "--output-dir",
        type=str,
        default=str(Path(__file__).resolve().parent / "artifacts_tuned"),
    )
    parser.add_argument("--mlflow-experiment", type=str, default="StressGuard-Iteration2-Tuning")
    parser.add_argument(
        "--mlflow-tracking-uri",
        type=str,
        default=str(Path(__file__).resolve().parent / "mlruns"),
    )
    args = parser.parse_args()

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    x, y = load_data(
        Path(args.data_path),
        args.label_mode,
        args.three_level_scheme,
        args.binary_threshold,
    )
    if args.feature_mode == "engineered":
        x = add_engineered_features(x)
    x_train, x_test, y_train, y_test = train_test_split(
        x, y, test_size=args.test_size, stratify=y, random_state=args.seed
    )

    tuned = tune_models(
        x_train=x_train,
        y_train=y_train,
        seed=args.seed,
        n_iter=args.n_iter,
        cv_splits=args.cv_splits,
    )

    estimators: Dict[str, object] = tuned["estimators"]
    cv_scores: Dict[str, float] = tuned["cv_scores"]
    best_params: Dict[str, Dict[str, object]] = tuned["best_params"]

    top_three_names = sorted(cv_scores, key=cv_scores.get, reverse=True)[:3]
    top_three = [(name, estimators[name]) for name in top_three_names]

    voting_model = VotingClassifier(estimators=top_three, voting="soft", n_jobs=-1)
    stacking_model = StackingClassifier(
        estimators=top_three,
        final_estimator=LogisticRegression(max_iter=3000, random_state=args.seed),
        stack_method="predict_proba",
        cv=args.cv_splits,
        n_jobs=-1,
    )

    candidates: Dict[str, object] = {**estimators}
    candidates["Voting_top3_tuned"] = voting_model
    candidates["Stacking_top3_tuned"] = stacking_model

    tracking_uri = args.mlflow_tracking_uri
    if "://" not in tracking_uri:
        tracking_uri = Path(tracking_uri).resolve().as_uri()
    mlflow.set_tracking_uri(tracking_uri)
    mlflow.set_experiment(args.mlflow_experiment)

    final_results: Dict[str, Dict[str, object]] = {}
    for name, model in candidates.items():
        result = evaluate_model(model, x_train, y_train, x_test, y_test)
        final_results[name] = result
        with mlflow.start_run(run_name=f"{name}_{args.label_mode}") as _:
            mlflow.log_params(
                {
                    "candidate_name": name,
                    "label_mode": args.label_mode,
                    "three_level_scheme": args.three_level_scheme,
                    "seed": args.seed,
                    "binary_threshold": args.binary_threshold,
                    "cv_splits": args.cv_splits,
                    "random_search_iter": args.n_iter,
                    "train_samples": len(x_train),
                    "test_samples": len(x_test),
                    "feature_count": x.shape[1],
                    "feature_mode": args.feature_mode,
                }
            )
            if name in best_params:
                mlflow.log_params({f"best_{k}": v for k, v in best_params[name].items()})
                mlflow.log_metric("cv_f1_weighted_best", cv_scores[name])
            mlflow.log_metrics(
                {
                    "test_accuracy": result["test_accuracy"],
                    "test_f1_weighted": result["test_f1_weighted"],
                }
            )
            with tempfile.TemporaryDirectory() as tmp_dir:
                tmp_path = Path(tmp_dir)
                report_path = tmp_path / "classification_report.json"
                cm_path = tmp_path / "confusion_matrix.json"
                report_path.write_text(json.dumps(result["classification_report"], indent=2), encoding="utf-8")
                cm_path.write_text(json.dumps(result["confusion_matrix"], indent=2), encoding="utf-8")
                mlflow.log_artifact(str(report_path), artifact_path="evaluation")
                mlflow.log_artifact(str(cm_path), artifact_path="evaluation")
            mlflow.sklearn.log_model(model, name="model")

    best_name = max(final_results, key=lambda n: final_results[n]["test_f1_weighted"])
    best_result = final_results[best_name]
    best_model = candidates[best_name]

    model_path = output_dir / "best_tuned_model.joblib"
    report_path = output_dir / "tuning_report.json"
    summary_path = output_dir / "tuning_summary.txt"

    import joblib  # local import keeps global imports tidy

    joblib.dump(best_model, model_path)

    payload = {
        "label_mode": args.label_mode,
        "three_level_scheme": args.three_level_scheme,
        "binary_threshold": args.binary_threshold,
        "feature_mode": args.feature_mode,
        "dataset_path": args.data_path,
        "train_size": len(x_train),
        "test_size": len(x_test),
        "search_cv_best_f1": cv_scores,
        "best_params": best_params,
        "final_results": final_results,
        "best_candidate": best_name,
        "best_candidate_test_result": best_result,
        "top_three_for_ensembles": top_three_names,
        "mlflow_experiment": args.mlflow_experiment,
        "mlflow_tracking_uri": tracking_uri,
    }
    report_path.write_text(json.dumps(payload, indent=2), encoding="utf-8")

    lines = [
        "StressGuard Tuning Summary",
        f"Label mode: {args.label_mode}",
        f"Three-level scheme: {args.three_level_scheme}",
        f"Binary threshold: {args.binary_threshold}",
        f"Feature mode: {args.feature_mode}",
        f"Train samples: {len(x_train)} | Test samples: {len(x_test)}",
        f"RandomizedSearch iterations per model: {args.n_iter}",
        "",
        "CV best weighted F1 (tuned base models):",
    ]
    for name in sorted(cv_scores, key=cv_scores.get, reverse=True):
        lines.append(f"- {name}: {cv_scores[name]:.4f}")
    lines.extend(
        [
            "",
            "Test weighted F1 (all tuned candidates):",
        ]
    )
    for name in sorted(final_results, key=lambda n: final_results[n]["test_f1_weighted"], reverse=True):
        lines.append(
            f"- {name}: {final_results[name]['test_f1_weighted']:.4f} | acc {final_results[name]['test_accuracy']:.4f}"
        )
    lines.extend(
        [
            "",
            f"Selected best candidate: {best_name}",
            f"Best test weighted F1: {best_result['test_f1_weighted']:.4f}",
            f"Best test accuracy: {best_result['test_accuracy']:.4f}",
            f"Saved model: {model_path}",
            f"Saved report: {report_path}",
        ]
    )
    summary_path.write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()

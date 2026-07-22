# StressGuard Iteration 2 - ML Engine

This folder contains the Iteration 2 training pipeline for StressGuard using the balanced dataset from Iteration 1.

## Dataset

- Primary dataset: `../StressGuard_Iteration1_Balanced_Data.csv`
- Target column: `Target_Stress_Level`

## Implemented Model Candidates

- `RandomForest`
- `XGBoost`
- `LogisticRegression` (baseline)
- `Stacking_RF_XGB_ANN` (base learners: RF + XGB, meta-learner: ANN/MLP)

This follows your planned Module 2 direction (stacking engine).

## How To Train

Run from project root:

```bash
python ml_engine/train_ml_engine.py --label-mode three-level
```

Optional 10-level score mode:

```bash
python ml_engine/train_ml_engine.py --label-mode score-10 --output-dir ml_engine/artifacts_score10
```

With explicit MLflow settings:

```bash
python ml_engine/train_ml_engine.py --label-mode three-level --mlflow-experiment StressGuard-Iteration2 --mlflow-tracking-uri ml_engine/mlruns
```

## Label Modes

- `three-level` (recommended): maps stress score to
  - `0 = Relaxed (1-4)`
  - `1 = Normal (5-7)`
  - `2 = Stressed (8-10)`
- `score-10`: predicts full stress score classes (0-9 internally, mapped from 1-10 source labels)

## Outputs

Each run saves:

- `best_model.joblib` - selected best model by CV weighted-F1
- `training_report.json` - full metrics (CV, test results, classification report, confusion matrix)
- `training_summary.txt` - quick textual summary

Default output directory:

- `ml_engine/artifacts/` (or custom via `--output-dir`)

## MLflow Tracking

The script now logs each model candidate (`RandomForest`, `XGBoost`, `LogisticRegression`, `Stacking_RF_XGB_ANN`) as a separate MLflow run with:

- Params (label mode, seed, train/test size, feature count, dataset path)
- Metrics (CV accuracy/F1 and test accuracy/F1)
- Artifacts:
  - `evaluation/classification_report.json`
  - `evaluation/confusion_matrix.json`
- Model artifact via `mlflow.sklearn.log_model(...)`

Start the UI from project root:

```bash
python -m mlflow ui --backend-store-uri "file:///D:/FYP/ml_engine/mlruns" --port 5000
```

Then open:

- [http://127.0.0.1:5000](http://127.0.0.1:5000)

## Current Baseline Results

### Three-Level Classification

- Best model: `Stacking_RF_XGB_ANN`
- Test Accuracy: `0.7712`
- Test Weighted-F1: `0.7646`

### 10-Level Classification

- Best model: `RandomForest`
- Test Accuracy: `0.5212`
- Test Weighted-F1: `0.5071`

Conclusion: three-level stress detection is currently stronger and should be the primary target for real-time engine deployment.

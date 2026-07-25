"""
Generate fixed test samples with expected probabilities, for Python-vs-Android validation.

Writes a JSON file the Android instrumented test reads. For each sample the test must
independently reproduce two things:

  1. the 22-value feature vector, built by StressFeatureBuilder from the profile and vitals
  2. the class probabilities, from running the same three ONNX graphs and soft-voting

The reference probabilities here come from onnxruntime rather than sklearn, because that is
what Android actually executes. evaluate_bundle.py separately confirms the ONNX graphs match
the sklearn estimator, so agreement with these values chains all the way back to training.

Samples deliberately cover the drop_first baseline categories (Female / Accountant / Normal,
all encoded as all-zero flags) and both ends of each vital's trained range, since those are
where an encoding or ordering mistake shows up.

Usage:
    python make_parity_samples.py
    python make_parity_samples.py --bundle mobile_export/binary_voting_top3_raw
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Dict, List

import numpy as np
import onnxruntime as ort

HERE = Path(__file__).resolve().parent

# (name, age, gender, occupation, bmi, heart_rate, daily_steps, sleep_hours)
# Vitals stay inside the training ranges: HR 43-109, steps 1000-16036, sleep 5.1-10.0.
SAMPLES: List[tuple] = [
    ("baseline_categories_all_zero", 34, "Female", "Accountant", "Normal", 72, 6000, 7.5),
    ("male_flag_only", 34, "Male", "Accountant", "Normal", 72, 6000, 7.5),
    ("nurse_overweight", 41, "Male", "Nurse", "Overweight", 88, 4200, 6.4),
    ("software_engineer_obese", 29, "Female", "Software Engineer", "Obese", 95, 3100, 5.8),
    ("student_underweight", 18, "Male", "Student", "Underweight", 65, 9800, 8.9),
    ("doctor_normal", 52, "Female", "Doctor", "Normal", 78, 5400, 6.9),
    ("teacher_min_age", 18, "Female", "Teacher", "Normal", 70, 7000, 7.2),
    ("writer_max_age", 80, "Male", "Writer", "Overweight", 84, 2600, 6.1),
    ("lawyer_min_heart_rate", 45, "Male", "Lawyer", "Normal", 43, 8200, 8.4),
    ("manager_max_heart_rate", 45, "Female", "Manager", "Obese", 109, 1500, 5.2),
    ("chef_min_steps", 37, "Male", "Chef", "Overweight", 91, 1000, 5.9),
    ("artist_max_steps", 26, "Female", "Artist", "Normal", 68, 16036, 9.1),
    ("engineer_min_sleep", 33, "Male", "Engineer", "Normal", 97, 3300, 5.1),
    ("scientist_max_sleep", 61, "Female", "Scientist", "Underweight", 61, 7700, 10.0),
    ("salesperson_mid", 48, "Male", "Salesperson", "Normal", 80, 5000, 7.0),
    ("sales_representative_mid", 48, "Female", "Sales Representative", "Overweight", 86, 4400, 6.6),
    ("all_extremes_low", 18, "Female", "Accountant", "Normal", 43, 16036, 10.0),
    ("all_extremes_high", 80, "Male", "Manager", "Obese", 109, 1000, 5.1),
    ("typical_relaxed", 30, "Female", "Teacher", "Normal", 62, 11000, 8.3),
    ("typical_stressed", 44, "Male", "Sales Representative", "Overweight", 105, 1200, 5.3),
]

OCCUPATION_FLAGS = [
    "Artist", "Chef", "Doctor", "Engineer", "Lawyer", "Manager", "Nurse",
    "Sales Representative", "Salesperson", "Scientist", "Software Engineer",
    "Student", "Teacher", "Writer",
]
BMI_FLAGS = ["Obese", "Overweight", "Underweight"]


def feature_map(age: int, gender: str, occupation: str, bmi: str,
                heart_rate: int, steps: int, sleep: float) -> Dict[str, float]:
    """Mirrors StressFeatureBuilder.featureMap on the Kotlin side."""
    values: Dict[str, float] = {
        "Age": float(age),
        "Heart Rate": float(heart_rate),
        "Daily Steps": float(steps),
        "Sleep Duration": float(sleep),
        "Gender_Male": 1.0 if gender == "Male" else 0.0,
    }
    for name in OCCUPATION_FLAGS:
        values[f"Occupation_{name}"] = 1.0 if occupation == name else 0.0
    for name in BMI_FLAGS:
        values[f"BMI Category_{name}"] = 1.0 if bmi == name else 0.0
    return values


def probabilities(sessions: List[tuple], x: np.ndarray, n_classes: int) -> np.ndarray:
    """Equal-weight soft vote over the exported graphs, as StressInferenceService does."""
    total = np.zeros((x.shape[0], n_classes), dtype=np.float64)
    for session, prob_name in sessions:
        raw = session.run([prob_name], {session.get_inputs()[0].name: x})[0]
        if isinstance(raw, list):  # skl2onnx ZipMap
            out = np.zeros_like(total)
            for row, mapping in enumerate(raw):
                for key, value in mapping.items():
                    out[row, int(key)] = value
        else:
            out = np.asarray(raw, dtype=np.float64).reshape(x.shape[0], n_classes)
        total += out / len(sessions)
    return total


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--bundle", type=str, default="mobile_export/binary_voting_top3_raw")
    parser.add_argument(
        "--out",
        type=str,
        default="../app/src/androidTest/assets/parity_samples.json",
        help="Where the Android instrumented test reads it from",
    )
    parser.add_argument("--tolerance", type=float, default=1e-4)
    args = parser.parse_args()

    bundle = Path(args.bundle)
    if not bundle.is_absolute():
        bundle = HERE / bundle
    manifest = json.loads((bundle / "stressguard_mobile_manifest.json").read_text(encoding="utf-8"))

    feature_names: List[str] = manifest["feature_names"]
    n_classes = int(manifest["n_classes"])
    label = manifest["label"]
    class_labels = [label[f"class_{i}"] for i in range(n_classes)]

    sessions = []
    for member in manifest["ensemble"]["members"]:
        session = ort.InferenceSession(
            str(bundle / member["onnx_file"]), providers=["CPUExecutionProvider"]
        )
        names = [o.name for o in session.get_outputs()]
        sessions.append((session, next(n for n in names if "probab" in n.lower())))

    rows: List[Dict[str, Any]] = []
    matrix = []
    for name, age, gender, occupation, bmi, hr, steps, sleep in SAMPLES:
        values = feature_map(age, gender, occupation, bmi, hr, steps, sleep)
        missing = [f for f in feature_names if f not in values]
        if missing:
            raise KeyError(f"sample {name!r} cannot supply {missing}")
        vector = [values[f] for f in feature_names]
        matrix.append(vector)
        rows.append({
            "name": name,
            "profile": {"age": age, "gender": gender, "occupation": occupation, "bmi": bmi},
            "vitals": {"heartRate": hr, "dailySteps": steps, "sleepHours": sleep},
            "features": vector,
        })

    x = np.ascontiguousarray(np.array(matrix, dtype=np.float32))
    probs = probabilities(sessions, x, n_classes)
    for row, p in zip(rows, probs):
        row["probabilities"] = [round(float(v), 9) for v in p]
        row["expectedLabel"] = class_labels[int(np.argmax(p))]

    payload = {
        "generatedBy": "ml_engine/make_parity_samples.py",
        "bundle": bundle.name,
        "modelVersion": f"{manifest['selected_model']}/{manifest['export_kind']}",
        "inputUnits": manifest.get("input_units", "raw_physical"),
        "featureNames": feature_names,
        "classLabels": class_labels,
        "tolerance": args.tolerance,
        "note": (
            "Reference values from onnxruntime on the same .onnx files Android ships. "
            "Regenerate with make_parity_samples.py whenever the bundle is re-exported."
        ),
        "samples": rows,
    }

    out = Path(args.out)
    if not out.is_absolute():
        out = (HERE / out).resolve()
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, indent=2), encoding="utf-8")

    print(f"bundle        {bundle.name}")
    print(f"model         {payload['modelVersion']}")
    print(f"classes       {class_labels}")
    print(f"samples       {len(rows)}")
    print(f"tolerance     {args.tolerance:g}")
    print(f"wrote         {out}")
    print("\nlabel distribution across samples:")
    for name in class_labels:
        count = sum(1 for r in rows if r["expectedLabel"] == name)
        print(f"  {name:<16} {count}")
    print("\nfirst sample:")
    print(f"  {rows[0]['name']}: probabilities={rows[0]['probabilities']} -> {rows[0]['expectedLabel']}")


if __name__ == "__main__":
    main()

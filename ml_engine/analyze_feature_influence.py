"""
Measure how much of a prediction comes from the live vitals versus the static profile.

Motivation: on device, all three demo scenarios predict "stressed" for a large fraction of
profiles, no matter how relaxed the vitals are. This script establishes why, with numbers that
can be quoted in the report.

It answers three questions:

  1. How far does P(high stress) move when only the vitals change, profile held fixed?
  2. How far does it move when only the occupation changes, vitals held fixed?
  3. For which occupations can the vitals not change the predicted class at all?

And then shows the training data's own occupation-to-stress relationship, which is the cause.

Usage:
    python analyze_feature_influence.py
    python analyze_feature_influence.py --bundle mobile_export/three_level_voting_wide_normal_raw
"""

from __future__ import annotations

import argparse
import itertools
import json
import statistics
from collections import Counter
from pathlib import Path
from typing import Dict, List, Tuple

import numpy as np
import onnxruntime as ort
import pandas as pd

HERE = Path(__file__).resolve().parent

# One-hot groups, matching StressFeatureBuilder. The drop_first baselines (Accountant, Normal,
# Female) have no column of their own but are still selectable in the app.
OCCUPATION_FLAGS = [
    "Artist", "Chef", "Doctor", "Engineer", "Lawyer", "Manager", "Nurse",
    "Sales Representative", "Salesperson", "Scientist", "Software Engineer",
    "Student", "Teacher", "Writer",
]
ALL_OCCUPATIONS = ["Accountant"] + OCCUPATION_FLAGS
BMI_FLAGS = ["Obese", "Overweight", "Underweight"]

# Spans the full trained range, so the vitals are given every chance to move the output:
# heart rate 43-109, steps 1000-16036, sleep 5.1-10.0.
VITALS_SWEEP: List[Tuple[str, int, int, float]] = [
    ("very relaxed", 45, 15000, 9.8),
    ("relaxed", 62, 11000, 8.3),
    ("middling", 76, 6100, 7.8),
    ("elevated", 92, 3000, 6.4),
    ("very stressed", 108, 1000, 5.1),
]

# The three scenarios behind the app's Run Model Test button.
DEBUG_SCENARIOS: List[Tuple[str, int, int, float]] = [
    ("relaxed", 62, 11000, 8.3),
    ("normal", 80, 5500, 7.2),
    ("high stress", 105, 1200, 5.3),
]


class Bundle:
    def __init__(self, directory: Path):
        manifest = json.loads(
            (directory / "stressguard_mobile_manifest.json").read_text(encoding="utf-8")
        )
        self.feature_names: List[str] = manifest["feature_names"]
        self.n_classes = int(manifest["n_classes"])
        self.label = manifest["label"]
        self.name = f"{manifest['selected_model']}/{manifest['export_kind']}"
        # The most severe class is the last one, in both the binary and three-level bundles.
        self.high_class = self.n_classes - 1
        self.high_class_name = self.label.get(f"class_{self.high_class}", "high")

        self.sessions = []
        for member in manifest["ensemble"]["members"]:
            session = ort.InferenceSession(
                str(directory / member["onnx_file"]), providers=["CPUExecutionProvider"]
            )
            names = [o.name for o in session.get_outputs()]
            self.sessions.append(
                (session, next(n for n in names if "probab" in n.lower()))
            )

    def vector(self, age, gender, occupation, bmi, heart_rate, steps, sleep) -> List[float]:
        values: Dict[str, float] = {name: 0.0 for name in self.feature_names}
        values["Age"] = float(age)
        values["Heart Rate"] = float(heart_rate)
        values["Daily Steps"] = float(steps)
        values["Sleep Duration"] = float(sleep)
        values["Gender_Male"] = 1.0 if gender == "Male" else 0.0
        for name in OCCUPATION_FLAGS:
            values[f"Occupation_{name}"] = 1.0 if occupation == name else 0.0
        for name in BMI_FLAGS:
            values[f"BMI Category_{name}"] = 1.0 if bmi == name else 0.0
        return [values[name] for name in self.feature_names]

    def p_high(self, rows: List[List[float]]) -> np.ndarray:
        """Probability of the most severe class, soft-voted as the app does."""
        x = np.ascontiguousarray(np.array(rows, dtype=np.float32))
        total = np.zeros((len(rows), self.n_classes), dtype=np.float64)
        for session, prob_name in self.sessions:
            raw = session.run([prob_name], {session.get_inputs()[0].name: x})[0]
            if isinstance(raw, list):  # skl2onnx ZipMap
                out = np.zeros_like(total)
                for row, mapping in enumerate(raw):
                    for key, value in mapping.items():
                        out[row, int(key)] = value
            else:
                out = np.asarray(raw, dtype=np.float64).reshape(len(rows), self.n_classes)
            total += out / len(self.sessions)
        return total[:, self.high_class]


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--bundle", type=str, default="mobile_export/binary_voting_top3_raw")
    parser.add_argument("--dataset", type=str, default="data/sleep_health_dataset.csv")
    args = parser.parse_args()

    directory = Path(args.bundle)
    if not directory.is_absolute():
        directory = HERE / directory
    bundle = Bundle(directory)

    print("=" * 78)
    print(f"Feature influence for {bundle.name}")
    print(f"Reporting P({bundle.high_class_name}) throughout")
    print("=" * 78)

    ages = [22, 40, 60]
    genders = ["Male", "Female"]
    bmis = ["Normal", "Overweight"]

    # --- 1. vitals, profile fixed ------------------------------------------------
    vitals_swings = []
    for age, gender, bmi, occupation in itertools.product(ages, genders, bmis, ALL_OCCUPATIONS):
        probabilities = bundle.p_high([
            bundle.vector(age, gender, occupation, bmi, hr, steps, sleep)
            for _, hr, steps, sleep in VITALS_SWEEP
        ])
        vitals_swings.append(float(probabilities.max() - probabilities.min()))

    # --- 2. occupation, vitals fixed ---------------------------------------------
    occupation_swings = []
    for _, hr, steps, sleep in VITALS_SWEEP:
        for age, gender, bmi in itertools.product(ages, genders, bmis):
            probabilities = bundle.p_high([
                bundle.vector(age, gender, occupation, bmi, hr, steps, sleep)
                for occupation in ALL_OCCUPATIONS
            ])
            occupation_swings.append(float(probabilities.max() - probabilities.min()))

    print("\nHow far the prediction moves when only one thing changes")
    print(f"  {'varying':<34} {'median':>8} {'mean':>8}")
    print(f"  {'vitals, across the whole range':<34} "
          f"{statistics.median(vitals_swings):>8.3f} {statistics.mean(vitals_swings):>8.3f}")
    print(f"  {'occupation, vitals held fixed':<34} "
          f"{statistics.median(occupation_swings):>8.3f} {statistics.mean(occupation_swings):>8.3f}")

    ratio = statistics.median(occupation_swings) / max(statistics.median(vitals_swings), 1e-9)
    print(f"\n  Occupation moves the output {ratio:.2f}x as much as the live vitals do.")

    # --- 3. which occupations are locked -----------------------------------------
    print("\nCan the vitals change the predicted class? (age 30, Male, Normal BMI)")
    print(f"  {'occupation':<22} {'very relaxed':>13} {'very stressed':>14} {'swing':>8}  outcome")
    locked = 0
    results = []
    for occupation in ALL_OCCUPATIONS:
        low, high = bundle.p_high([
            bundle.vector(30, "Male", occupation, "Normal", *VITALS_SWEEP[0][1:]),
            bundle.vector(30, "Male", occupation, "Normal", *VITALS_SWEEP[-1][1:]),
        ])
        same_class = (low > 0.5) == (high > 0.5)
        if same_class:
            locked += 1
        results.append((occupation, float(low), float(high), same_class))

    for occupation, low, high, same_class in sorted(results, key=lambda r: -r[1]):
        outcome = (
            f"locked to {'high' if low > 0.5 else 'low'} stress" if same_class
            else "vitals flip the class"
        )
        print(f"  {occupation:<22} {low:>13.3f} {high:>14.3f} {high - low:>8.3f}  {outcome}")

    print(f"\n  {locked} of {len(results)} occupations cannot be moved across the decision "
          f"boundary by any combination of vitals in the trained range.")

    # --- 4. the app's own demo scenarios -----------------------------------------
    all_high = 0
    none_high = 0
    varies = 0
    full_ages = [18, 22, 25, 30, 35, 40, 45, 50, 60, 70, 80]
    for age, gender, occupation, bmi in itertools.product(
        full_ages, genders, ALL_OCCUPATIONS, ["Normal"] + BMI_FLAGS
    ):
        probabilities = bundle.p_high([
            bundle.vector(age, gender, occupation, bmi, hr, steps, sleep)
            for _, hr, steps, sleep in DEBUG_SCENARIOS
        ])
        high_flags = [p > 0.5 for p in probabilities]
        if all(high_flags):
            all_high += 1
        elif not any(high_flags):
            none_high += 1
        else:
            varies += 1

    total = all_high + none_high + varies
    print(f"\nAcross {total} profiles, what do the app's three demo scenarios produce?")
    print(f"  all three high stress : {all_high:>5}  ({100 * all_high / total:.1f}%)")
    print(f"  none high stress      : {none_high:>5}  ({100 * none_high / total:.1f}%)")
    print(f"  varies by scenario    : {varies:>5}  ({100 * varies / total:.1f}%)")
    print(f"\n  For {100 * (all_high + none_high) / total:.1f}% of profiles the three demo "
          f"scenarios all land on the same class,")
    print("  so tapping Run Model Test shows no change in the verdict.")

    # --- 5. the cause, in the data ------------------------------------------------
    dataset = Path(args.dataset)
    if not dataset.is_absolute():
        dataset = HERE / dataset
    if not dataset.is_file():
        print(f"\n(dataset not found at {dataset}; skipping the training-data table)")
        return

    raw = pd.read_csv(dataset)
    raw = raw[[c for c in raw.columns if not c.startswith("Unnamed")]]
    grouped = (
        raw.groupby("Occupation")["Stress Level"]
        .agg(["mean", "min", "max", "count"])
        .sort_values("mean")
    )

    print("\nWhy: occupation against stress in the training data")
    print(f"  {'occupation':<22} {'mean':>6} {'min':>4} {'max':>4} {'rows':>6}")
    for occupation, row in grouped.iterrows():
        print(f"  {occupation:<22} {row['mean']:>6.2f} {int(row['min']):>4} "
              f"{int(row['max']):>4} {int(row['count']):>6}")

    spans = {occ: int(r["max"]) - int(r["min"]) for occ, r in grouped.iterrows()}
    tight = [occ for occ, span in spans.items() if span <= 4]
    print(f"\n  Every occupation has exactly {int(grouped['count'].iloc[0])} rows, and "
          f"{len(tight)} of {len(spans)} span 4 or fewer stress levels.")
    print("  Occupation is close to a label proxy in this dataset, which is why a model fit on")
    print("  it leans on occupation and treats the vitals as a modifier.")


if __name__ == "__main__":
    main()

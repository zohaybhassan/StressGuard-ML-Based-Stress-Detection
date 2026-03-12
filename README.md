

# StressGuard: Multi-Modal Stress Detection & Intervention

StressGuard is an AI-driven health monitoring system designed to detect, analyze, and mitigate psychological stress using real-time wearable telemetry. The project utilizes a **Stacked Ensemble Learning** architecture to provide high-accuracy stress predictions on a 1-10 scale.

## 🚀 Key Features
* **Module 1 (ML Engine):** A hybrid architecture combining Random Forest, XGBoost, and an ANN Meta-Learner for robust stress classification.
* **Module 2 (Diagnostic Logic):** Rule-based personalization and risk assessment based on 100k+ medical history records.
* **Module 3 (Wearable Pipeline):** Real-time BLE streaming of Heart Rate and Step Count from Samsung Galaxy Watch 4.
* **Module 4 (Intervention):** Closed-loop haptic feedback and a Virtual Assistant for immediate stress de-escalation.

## 🛠️ Tech Stack
* **Machine Learning:** Python (Scikit-Learn, XGBoost, Imbalanced-Learn, Pandas)
* **Mobile Application:** Kotlin/Java (Android Studio)
* **Data Visualization:** MPAndroidChart
* **Database:** SQLite / Room Persistence Library
* **Architecture:** Stacked Generalization (Ensemble Learning)

## 📊 Iteration 1 Progress: Data Foundation
- ✅ Data Curation: Curated a hardware-aligned dataset excluding restricted features (BP).
- ✅ Preprocessing: Implemented Z-Score Normalization and One-Hot Encoding.
- ✅ Data Augmentation: Applied **SMOTE** to balance stress class distributions to 236 records per level.
- ✅ DB Design: Established a normalized schema for User Profiles and Sensor Telemetry.

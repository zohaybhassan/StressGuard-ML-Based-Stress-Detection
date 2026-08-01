package com.example.stressguard // UPDATE TO YOUR EXACT PACKAGE NAME

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.stressguard.data.AuthRepository
import com.example.stressguard.data.ProfileRepository
import com.example.stressguard.ui.fitSystemBars
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ProfileSetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_setup)
        fitSystemBars(top = findViewById(R.id.profileRoot))
        val editing = intent.getBooleanExtra(EXTRA_EDITING, false)

        // 1. Initialize UI Elements
        val etName = findViewById<TextInputEditText>(R.id.etName)
        val etAge = findViewById<TextInputEditText>(R.id.etAge)
        val dropdownGender = findViewById<AutoCompleteTextView>(R.id.dropdownGender)
        val dropdownOccupation = findViewById<AutoCompleteTextView>(R.id.dropdownOccupation)
        val dropdownBmi = findViewById<AutoCompleteTextView>(R.id.dropdownBmi)
        val btnSaveProfile = findViewById<MaterialButton>(R.id.btnSaveProfile)

        // Prefill from the signed-in identity. This comes from the Supabase session rather
        // than local storage, so it reflects the account actually authenticated.
        if (!editing) AuthRepository.displayName?.let { etName.setText(it) }

        // 2. Define the exact lists for the ML Model choices.
        // These must use the dataset's own category names, because StressFeatureBuilder
        // matches on them to set the one-hot flags. "Accountant" and "Normal" are the
        // drop_first baselines, so they are represented by all-zero flags rather than a
        // column of their own -- they still have to be selectable.
        val genders = arrayOf("Male", "Female")
        val occupations = arrayOf(
            "Accountant", "Artist", "Chef", "Doctor", "Engineer", "Lawyer", "Manager",
            "Nurse", "Sales Representative", "Salesperson", "Scientist",
            "Software Engineer", "Student", "Teacher", "Writer"
        )
        // The dataset carries these four as bare labels with no height, weight or BMI number
        // anywhere, so it documents no thresholds of its own. The WHO cut-offs are shown alongside
        // each label because without them the choice is guesswork: two people with the same body
        // would pick differently, and BMI is three of the model's 22 features.
        val bmiCategories = arrayOf(
            "Underweight",
            "Normal",
            "Overweight",
            "Obese",
        )
        val bmiLabels = arrayOf(
            "Underweight  (BMI under 18.5)",
            "Normal  (BMI 18.5 – 24.9)",
            "Overweight  (BMI 25.0 – 29.9)",
            "Obese  (BMI 30.0 and over)",
        )

        // 3. Attach the lists to the Dropdown menus. R.layout.item_dropdown rather than the
        // platform's simple_dropdown_item_1line, which draws with the system's own colours and so
        // rendered dark text on a dark sheet once the app gained a night theme.
        dropdownGender.setAdapter(ArrayAdapter(this, R.layout.item_dropdown, genders))
        dropdownOccupation.setAdapter(ArrayAdapter(this, R.layout.item_dropdown, occupations))

        // The dropdown shows the labels with their ranges; what gets saved is the bare category,
        // because that is the string StressFeatureBuilder matches on to set the one-hot flags and
        // the value the profiles table's CHECK constraint accepts.
        dropdownBmi.setAdapter(ArrayAdapter(this, R.layout.item_dropdown, bmiLabels))

        if (editing) {
            findViewById<MaterialButton>(R.id.btnProfileBack).apply {
                visibility = View.VISIBLE
                setOnClickListener { onBackPressedDispatcher.onBackPressed() }
            }
            findViewById<TextView>(R.id.tvProfileTitle).text = getString(R.string.profile_edit_title)
            findViewById<TextView>(R.id.tvProfileSubtitle).text =
                getString(R.string.profile_edit_subtitle)
            btnSaveProfile.text = getString(R.string.profile_save_changes)
            etName.setText(SessionManager.getUserName(this))
            etAge.setText(SessionManager.getUserAge(this)?.toString().orEmpty())
            dropdownGender.setText(SessionManager.getUserGender(this).orEmpty(), false)
            dropdownOccupation.setText(SessionManager.getUserOccupation(this).orEmpty(), false)
            val savedBmi = SessionManager.getUserBmi(this)
            val bmiIndex = bmiCategories.indexOf(savedBmi)
            dropdownBmi.setText(bmiLabels.getOrNull(bmiIndex).orEmpty(), false)
        }

        // 4. Handle Save Button Click
        btnSaveProfile.setOnClickListener {
            val name = etName.text.toString().trim()
            val age = etAge.text.toString().trim()
            val gender = dropdownGender.text.toString().trim()
            val occupation = dropdownOccupation.text.toString().trim()
            val bmiLabel = dropdownBmi.text.toString().trim()
            val bmi = bmiCategories.firstOrNull { bmiLabel.startsWith(it) }.orEmpty()

            // Basic Validation to ensure no fields are empty
            if (name.isEmpty() || age.isEmpty() || gender.isEmpty() || occupation.isEmpty() || bmi.isEmpty()) {
                Toast.makeText(this, "Please fill out all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // The model was trained on ages 18-80; outside that it is extrapolating, so
            // reject rather than silently predict from an unsupported profile.
            val ageValue = age.toIntOrNull()
            if (ageValue == null || ageValue < MIN_AGE || ageValue > MAX_AGE) {
                Toast.makeText(
                    this,
                    "Enter an age between $MIN_AGE and $MAX_AGE",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            // Save locally first. This is what inference reads, so it must not depend on the
            // network succeeding.
            SessionManager.saveProfile(
                context = this,
                name = name,
                age = ageValue,
                gender = gender,
                occupation = occupation,
                bmi = bmi
            )

            btnSaveProfile.isEnabled = false
            lifecycleScope.launch {
                // Bounded, because a dead network must not leave the user staring at a
                // disabled button. Either outcome proceeds: the profile is already saved, and
                // a later launch retries the push.
                val synced = withTimeoutOrNull(SYNC_TIMEOUT_MS) {
                    ProfileRepository.push(this@ProfileSetupActivity)
                } ?: false

                Toast.makeText(
                    this@ProfileSetupActivity,
                    if (synced) "Profile saved" else "Profile saved on this device",
                    Toast.LENGTH_SHORT,
                ).show()

                if (editing) {
                    finish()
                } else {
                    // On to the health checklist, which feeds the checkup recommendation.
                    startActivity(HealthChecklistActivity.setupIntent(this@ProfileSetupActivity))
                    finish()
                }
            }
        }
    }

    companion object {
        // Age range covered by the training dataset (ml_engine/data/sleep_health_dataset.csv).
        private const val MIN_AGE = 18
        private const val MAX_AGE = 80

        private const val SYNC_TIMEOUT_MS = 5_000L
        private const val EXTRA_EDITING = "editing"

        fun editIntent(context: Context) = Intent(context, ProfileSetupActivity::class.java)
            .putExtra(EXTRA_EDITING, true)
    }
}

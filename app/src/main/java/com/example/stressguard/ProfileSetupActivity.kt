package com.example.stressguard // UPDATE TO YOUR EXACT PACKAGE NAME

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class ProfileSetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_setup)

        // 1. Initialize UI Elements
        val etName = findViewById<TextInputEditText>(R.id.etName)
        val etAge = findViewById<TextInputEditText>(R.id.etAge)
        val dropdownGender = findViewById<AutoCompleteTextView>(R.id.dropdownGender)
        val dropdownOccupation = findViewById<AutoCompleteTextView>(R.id.dropdownOccupation)
        val dropdownBmi = findViewById<AutoCompleteTextView>(R.id.dropdownBmi)
        val btnSaveProfile = findViewById<MaterialButton>(R.id.btnSaveProfile)

        val googleName = SessionManager.getGoogleDisplayName(this)
        if (!googleName.isNullOrBlank()) {
            etName.setText(googleName)
        }

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
        val bmiCategories = arrayOf("Underweight", "Normal", "Overweight", "Obese")

        // 3. Attach the lists to the Dropdown menus
        val genderAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, genders)
        dropdownGender.setAdapter(genderAdapter)

        val occupationAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, occupations)
        dropdownOccupation.setAdapter(occupationAdapter)

        val bmiAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, bmiCategories)
        dropdownBmi.setAdapter(bmiAdapter)

        // 4. Handle Save Button Click
        btnSaveProfile.setOnClickListener {
            val name = etName.text.toString().trim()
            val age = etAge.text.toString().trim()
            val gender = dropdownGender.text.toString().trim()
            val occupation = dropdownOccupation.text.toString().trim()
            val bmi = dropdownBmi.text.toString().trim()

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

            SessionManager.saveProfile(
                context = this,
                name = name,
                age = ageValue,
                gender = gender,
                occupation = occupation,
                bmi = bmi
            )
            SessionManager.setSignedIn(this, true)

            // 6. Navigate to the next screen (e.g., MainActivity or Dashboard)
            Toast.makeText(this, "Profile Saved!", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, HomeDashboardActivity::class.java)
            startActivity(intent)
            finish() // Prevent user from going back to setup
        }
    }

    companion object {
        // Age range covered by the training dataset (ML_engine/data/sleep_health_dataset.csv).
        private const val MIN_AGE = 18
        private const val MAX_AGE = 80
    }
}

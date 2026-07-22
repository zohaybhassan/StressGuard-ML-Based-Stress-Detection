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

        // 2. Define the exact lists for the ML Model choices
        val genders = arrayOf("Male", "Female")
        val occupations = arrayOf(
            "Artist", "Chef", "Doctor", "Engineer", "Lawyer", "Manager",
            "Nurse", "Sales Representative", "Salesperson", "Scientist",
            "Software Engineer", "Student", "Teacher", "Writer"
        )
        // I added "Normal Weight" to your list just in case, but you can remove it if your ML doesn't use it!
        val bmiCategories = arrayOf("Underweight", "Normal Weight", "Overweight", "Obese")

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

            SessionManager.saveProfile(
                context = this,
                name = name,
                age = age.toInt(),
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
}

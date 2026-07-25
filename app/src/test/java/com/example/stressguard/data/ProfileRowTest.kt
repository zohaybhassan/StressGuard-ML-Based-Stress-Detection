package com.example.stressguard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signup trigger inserts a profile row the moment a user is created, so the app will
 * always find one. Completeness therefore has to be judged on the model columns, not on the
 * row's existence -- otherwise a brand new user skips profile setup and the dashboard sits at
 * PROFILE NEEDED forever.
 */
class ProfileRowTest {

    private val complete = ProfileRow(
        id = "0d3a1f60-0000-4000-8000-000000000001",
        displayName = "Fahad",
        age = 22,
        gender = "Male",
        occupation = "Student",
        bmiCategory = "Normal",
    )

    @Test
    fun rowWithEveryModelColumnIsComplete() {
        assertTrue(complete.isComplete)

        val profile = complete.toStressProfile()
        assertEquals(22, profile?.age)
        assertEquals("Male", profile?.gender)
        assertEquals("Student", profile?.occupation)
        assertEquals("Normal", profile?.bmi)
    }

    /** Exactly what the signup trigger leaves behind. */
    @Test
    fun rowCreatedByTheSignupTriggerIsNotComplete() {
        val fresh = ProfileRow(
            id = complete.id,
            displayName = "Fahad",
        )

        assertFalse(fresh.isComplete)
        assertNull(fresh.toStressProfile())
    }

    @Test
    fun anyMissingModelColumnMakesTheRowUnusable() {
        val variants = mapOf(
            "age" to complete.copy(age = null),
            "gender" to complete.copy(gender = null),
            "occupation" to complete.copy(occupation = null),
            "bmi" to complete.copy(bmiCategory = null),
        )

        variants.forEach { (missing, row) ->
            assertFalse("missing $missing should not be complete", row.isComplete)
            assertNull("missing $missing should not yield a profile", row.toStressProfile())
        }
    }

    /** Blank is what an empty form field round-trips to; it must not pass as a value. */
    @Test
    fun blankStringsAreTreatedAsMissing() {
        assertFalse(complete.copy(gender = "").isComplete)
        assertFalse(complete.copy(occupation = "   ").isComplete)
        assertFalse(complete.copy(bmiCategory = "").isComplete)
    }

    /** displayName is for the UI only; inference never reads it. */
    @Test
    fun displayNameIsNotRequiredForCompleteness() {
        assertTrue(complete.copy(displayName = null).isComplete)
    }
}

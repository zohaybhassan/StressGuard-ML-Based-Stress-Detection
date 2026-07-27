package com.example.stressguard.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `password_set` half of the profile row.
 *
 * This flag decides whether a user is sent to the set-password screen, so both directions of
 * getting it wrong are visible: a wrong `false` traps someone behind a screen they cannot pass, and
 * a wrong `true` leaves a Google account with no password and an email sign-in that will never
 * work.
 */
class ProfileRowTestPasswordSet {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun aGoogleSignUpIsReadAsHavingNoPassword() {
        val row = json.decodeFromString<ProfileRow>(
            """{"id":"u1","display_name":"Fahad","password_set":false}"""
        )

        assertFalse(row.passwordSet)
    }

    @Test
    fun anEmailSignUpIsReadAsHavingOne() {
        val row = json.decodeFromString<ProfileRow>(
            """{"id":"u1","password_set":true}"""
        )

        assertTrue(row.passwordSet)
    }

    /**
     * A server that has not run the migration returns no such column.
     *
     * Defaulting to true is the deliberate direction: the cost is a user who cannot sign in by
     * email until the migration is applied, rather than every user in the project being trapped
     * behind a set-password screen that cannot record its result.
     */
    @Test
    fun anAbsentColumnDefaultsToHavingOne() {
        val row = json.decodeFromString<ProfileRow>("""{"id":"u1","age":30}""")

        assertTrue(
            "a project missing the migration must not trap everyone on the password screen",
            row.passwordSet,
        )
    }

    /** The flag is independent of profile completeness; they answer different questions. */
    @Test
    fun passwordStateDoesNotAffectProfileCompleteness() {
        val complete = ProfileRow(
            id = "u1",
            age = 30,
            gender = "Male",
            occupation = "Engineer",
            bmiCategory = "Normal",
            passwordSet = false,
        )

        assertTrue(complete.isComplete)
        assertFalse(complete.passwordSet)
        assertEquals(30, complete.toStressProfile()?.age)
    }
}

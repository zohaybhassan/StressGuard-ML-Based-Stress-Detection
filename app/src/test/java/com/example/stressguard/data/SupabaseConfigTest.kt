package com.example.stressguard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The secret-key check is a safety net, not a nicety: a service_role key in the app bypasses
 * row-level security entirely, so every user would be able to read and delete every other
 * user's data. These tests pin the detection, especially for the legacy JWT form where the
 * role is base64-encoded and so invisible to a substring search.
 */
class SupabaseConfigTest {

    private val validUrl = "https://czaynybndzsvztngutbk.supabase.co"
    private val validKey = "sb_publishable_l4zGhcKT8NEHwCABmEtoew_FiX8Oc8Z"
    private val validClientId = "355796161084-jle4a82t2glupml95k3s7fm24hebadn1.apps.googleusercontent.com"

    /** Structurally a real Supabase legacy key: header.payload.signature, payload base64url. */
    private fun legacyKey(role: String): String {
        val encode = { s: String ->
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        }
        val header = encode("""{"alg":"HS256","typ":"JWT"}""")
        val payload = encode("""{"iss":"supabase","ref":"czaynybndzsvztngutbk","role":"$role","iat":1784999477}""")
        return "$header.$payload.notarealsignature"
    }

    @Test
    fun completeConfigurationHasNoProblems() {
        assertEquals(emptyList<String>(), SupabaseConfig.validate(validUrl, validKey, validClientId))
    }

    @Test
    fun missingValuesAreEachReported() {
        val problems = SupabaseConfig.validate("", "", "")

        assertEquals(3, problems.size)
        assertTrue(problems.any { it.contains("supabase.url") })
        assertTrue(problems.any { it.contains("supabase.publishableKey") })
        assertTrue(problems.any { it.contains("supabase.googleWebClientId") })
    }

    @Test
    fun newStyleSecretKeyIsRejected() {
        assertTrue(SupabaseConfig.isSecretKey("sb_secret_abcdefghijklmnopqrstuvwxyz"))

        val problems = SupabaseConfig.validate(validUrl, "sb_secret_abcdefghij", validClientId)
        assertTrue(problems.any { it.contains("SECRET key") })
    }

    /** The case that actually happened: a service_role JWT pasted in place of the anon key. */
    @Test
    fun legacyServiceRoleJwtIsRejected() {
        val key = legacyKey("service_role")

        assertFalse(
            "the role is base64-encoded, so a substring search cannot see it",
            key.contains("service_role"),
        )
        assertTrue("but decoding the payload must find it", SupabaseConfig.isSecretKey(key))

        val problems = SupabaseConfig.validate(validUrl, key, validClientId)
        assertTrue(problems.any { it.contains("SECRET key") })
    }

    @Test
    fun legacyAnonJwtIsAccepted() {
        val key = legacyKey("anon")

        assertFalse(SupabaseConfig.isSecretKey(key))
        assertEquals(emptyList<String>(), SupabaseConfig.validate(validUrl, key, validClientId))
    }

    @Test
    fun publishableKeyIsNotMistakenForASecret() {
        assertFalse(SupabaseConfig.isSecretKey(validKey))
    }

    @Test
    fun malformedKeyIsNotTreatedAsSecret() {
        // Must not throw on things that only look like a JWT.
        assertFalse(SupabaseConfig.isSecretKey("not.a.jwt"))
        assertFalse(SupabaseConfig.isSecretKey("only.two"))
        assertFalse(SupabaseConfig.isSecretKey(""))
    }

    @Test
    fun httpUrlIsRejected() {
        val problems = SupabaseConfig.validate("http://insecure.supabase.co", validKey, validClientId)
        assertTrue(problems.any { it.contains("https://") })
    }

    @Test
    fun clientIdThatIsNotAGoogleOAuthIdIsRejected() {
        val problems = SupabaseConfig.validate(validUrl, validKey, "355796161084")
        assertTrue(problems.any { it.contains("googleWebClientId") })
    }

    /**
     * The two problem surfaces have to stay separate.
     *
     * A missing Google client ID disables the Google button only; email and password sign-in is
     * unaffected. Folding it into the backend check — which is what happened before email auth
     * existed — makes a project configured for email-only look completely broken.
     */
    @Test
    fun aMissingGoogleClientIdIsNotABackendProblem() {
        val backendOnly = SupabaseConfig.validate(validUrl, validKey, "")

        assertEquals(1, backendOnly.size)
        assertTrue(backendOnly.single().contains("googleWebClientId"))
    }

    @Test
    fun aMissingUrlOrKeyIsReportedIndependentlyOfGoogle() {
        val problems = SupabaseConfig.validate("", "", validClientId)

        assertEquals(2, problems.size)
        assertTrue(problems.any { it.contains("supabase.url") })
        assertTrue(problems.any { it.contains("supabase.publishableKey") })
        assertFalse(problems.any { it.contains("googleWebClientId") })
    }
}

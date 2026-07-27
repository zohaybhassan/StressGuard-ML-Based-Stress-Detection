package com.example.stressguard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The local checks the login form runs before spending a round trip.
 *
 * Worth pinning because the messages are what the user acts on: a rule that rejects a valid address
 * blocks registration entirely, and one that accepts an invalid password produces a 422 whose text
 * means nothing on a phone.
 */
class CredentialRulesTest {

    @Test
    fun ordinaryAddressesAreAccepted() {
        assertNull(CredentialRules.emailProblem("fahad@example.com"))
        assertNull(CredentialRules.emailProblem("first.last+tag@sub.domain.co.uk"))
        assertNull(CredentialRules.emailProblem("a@b.co"))
    }

    @Test
    fun malformedAddressesAreRejected() {
        assertNotNull(CredentialRules.emailProblem(""))
        assertNotNull("no @", CredentialRules.emailProblem("fahad.example.com"))
        assertNotNull("no domain dot", CredentialRules.emailProblem("fahad@example"))
        assertNotNull("nothing before the @", CredentialRules.emailProblem("@example.com"))
        assertNotNull("spaces", CredentialRules.emailProblem("fah ad@example.com"))
    }

    /** Typing an address with a stray space is common enough that it must not be an error. */
    @Test
    fun surroundingWhitespaceIsTolerated() {
        assertNull(CredentialRules.emailProblem("  fahad@example.com  "))
    }

    @Test
    fun blankAndShortPasswordsAreRejectedWithDifferentMessages() {
        val blank = CredentialRules.passwordProblem("")
        val short = CredentialRules.passwordProblem("abc")

        assertNotNull(blank)
        assertNotNull(short)
        assertEquals("Enter a password", blank)
        // "too short" and "you typed nothing" are different mistakes and get different advice.
        assertEquals("Use at least ${CredentialRules.MIN_PASSWORD_LENGTH} characters", short)
    }

    @Test
    fun passwordAtTheMinimumLengthIsAccepted() {
        val exactly = "a".repeat(CredentialRules.MIN_PASSWORD_LENGTH)

        assertNull(CredentialRules.passwordProblem(exactly))
        assertNotNull(CredentialRules.passwordProblem(exactly.dropLast(1)))
    }

    @Test
    fun confirmationMustMatchExactly() {
        assertNull(CredentialRules.confirmationProblem("hunter22", "hunter22"))
        assertNotNull(CredentialRules.confirmationProblem("hunter22", "hunter23"))
        // Not trimmed: a trailing space is part of the password, so silently ignoring it here
        // would accept a confirmation that does not match what will be sent.
        assertNotNull(CredentialRules.confirmationProblem("hunter22", "hunter22 "))
    }
}

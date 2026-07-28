package com.example.stressguard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline crisis check.
 *
 * The Edge Function owns this while it is reachable, and its own tests live beside it in
 * `supabase/functions/chat/safety_test.ts`. This copy covers the case the server cannot: no
 * network. Both are tested because a divergence between them would be silent, and the failure it
 * produces — a breathing exercise offered to someone in danger — is the one this app must never
 * produce.
 */
class CrisisCheckTest {

    @Test
    fun `statements of suicidal intent are caught`() {
        assertTrue(CrisisCheck.isCrisis("i want to die"))
        assertTrue(CrisisCheck.isCrisis("I want to kill myself"))
        assertTrue(CrisisCheck.isCrisis("thinking about ending my life"))
        assertTrue(CrisisCheck.isCrisis("everyone would be better off dead"))
        assertTrue(CrisisCheck.isCrisis("I have been having suicidal thoughts"))
        assertTrue(CrisisCheck.isCrisis("there is nothing to live for"))
    }

    @Test
    fun `self-harm is caught as well as suicide`() {
        assertTrue(CrisisCheck.isCrisis("i want to hurt myself"))
        assertTrue(CrisisCheck.isCrisis("I have been cutting myself again"))
        assertTrue(CrisisCheck.isCrisis("thinking about self-harm"))
        assertTrue(CrisisCheck.isCrisis("I took an overdose last night"))
    }

    /** The realistic failure is not clever evasion; it is ordinary typing. */
    @Test
    fun `casing and punctuation do not defeat the check`() {
        assertTrue(CrisisCheck.isCrisis("I WANT TO DIE"))
        assertTrue(CrisisCheck.isCrisis("i want to... die"))
        assertTrue(CrisisCheck.isCrisis("I want to die!!!"))
        assertTrue(CrisisCheck.isCrisis("Honestly? I want to die."))
        assertTrue(CrisisCheck.isCrisis("i   want   to   die"))
    }

    /**
     * The app exists to talk to people who say things like this. Answering them with a helpline
     * would be useless, and would teach the user that the feature does not listen.
     */
    @Test
    fun `ordinary stressed speech is left alone`() {
        assertFalse(CrisisCheck.isCrisis("this deadline is killing me"))
        assertFalse(CrisisCheck.isCrisis("my boss is murdering me with these hours"))
        assertFalse(CrisisCheck.isCrisis("I'm dead tired"))
        assertFalse(CrisisCheck.isCrisis("I'm dying to get some sleep"))
        assertFalse(CrisisCheck.isCrisis("that presentation nearly killed me"))
        assertFalse(CrisisCheck.isCrisis("I could kill for a coffee"))
    }

    @Test
    fun `the conversations this feature is for pass through`() {
        assertFalse(CrisisCheck.isCrisis("I feel overwhelmed by work"))
        assertFalse(CrisisCheck.isCrisis("I can't sleep and my heart is racing"))
        assertFalse(CrisisCheck.isCrisis("I had a panic attack this morning"))
        assertFalse(CrisisCheck.isCrisis("everything feels like too much right now"))
    }

    @Test
    fun `normalise strips what stands between typing and matching`() {
        assertEquals("i want to die", CrisisCheck.normalise("  I WANT   to,,, DIE!!  "))
        assertEquals("self harm", CrisisCheck.normalise("self-harm"))
    }

    /**
     * The offline reply must work offline. Naming a website or telling someone to search would be
     * advice they cannot act on, which is the failure this whole path exists to avoid.
     */
    @Test
    fun `the offline reply gives numbers that work without data`() {
        assertTrue(CrisisCheck.REPLY.contains("1122"))
        assertTrue(CrisisCheck.REPLY.contains("0311 7786264"))
        assertTrue(CrisisCheck.REPLY.contains("without an internet connection"))
        assertFalse("must not send someone offline to a website", CrisisCheck.REPLY.contains("http"))
    }

    @Test
    fun `the offline reply does not pretend to be a person`() {
        assertTrue(CrisisCheck.REPLY.contains("an app rather than a person"))
    }
}

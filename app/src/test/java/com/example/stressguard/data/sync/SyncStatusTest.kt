package com.example.stressguard.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the dashboard's sync line says.
 *
 * The failure mode worth guarding against is a pending count with no explanation: 99 rows queued
 * looks like a broken sync until you know nobody is signed in. Each "nothing is happening" case
 * has to say why.
 */
class SyncStatusTest {

    private val now = 1785056400000L

    private fun status(
        pending: Int = 0,
        lastSuccessEpochMs: Long? = null,
        signedIn: Boolean = true,
        backendConfigured: Boolean = true,
    ) = SyncStatus(pending, lastSuccessEpochMs, signedIn, backendConfigured)

    @Test
    fun `an unconfigured backend says so rather than showing a queue`() {
        val text = status(pending = 99, backendConfigured = false).describe(now)

        assertTrue(text, text.contains("not configured"))
        assertTrue(text, text.contains("99"))
    }

    @Test
    fun `being signed out is named as the reason nothing syncs`() {
        val text = status(pending = 99, signedIn = false).describe(now)

        assertTrue(text, text.contains("Sign in"))
        assertTrue(text, text.contains("99"))
    }

    @Test
    fun `a drained queue reports when it last succeeded`() {
        val text = status(pending = 0, lastSuccessEpochMs = now - 4 * 60_000).describe(now)

        assertEquals("Synced 4 min ago", text)
    }

    @Test
    fun `a queue that has never synced does not claim a last success`() {
        val text = status(pending = 12, lastSuccessEpochMs = null).describe(now)

        assertEquals("12 waiting to sync", text)
    }

    @Test
    fun `a partial queue reports both the backlog and the last success`() {
        val text = status(pending = 12, lastSuccessEpochMs = now - 2 * 3_600_000).describe(now)

        assertTrue(text, text.contains("12 waiting"))
        assertTrue(text, text.contains("2 h ago"))
    }

    @Test
    fun `a fresh install with nothing to send says so`() {
        assertEquals("Nothing to sync", status().describe(now))
    }

    @Test
    fun `a backwards clock does not produce a sync in the future`() {
        // A user changing the device clock, or a network time correction, would otherwise make
        // the elapsed time negative and render as a nonsense duration.
        val text = status(pending = 0, lastSuccessEpochMs = now + 60_000).describe(now)

        assertEquals("Synced recently", text)
    }
}

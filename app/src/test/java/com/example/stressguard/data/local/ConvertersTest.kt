package com.example.stressguard.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probability vector round-trip.
 *
 * Worth pinning because a silent failure here is invisible: the argmax and label are stored in
 * their own columns, so a corrupted probability list would only show up much later, when a
 * trend view or a re-analysis read the history back.
 */
class ConvertersTest {

    private val converters = Converters()

    @Test
    fun binaryProbabilitiesRoundTrip() {
        val original = listOf(0.7259827f, 0.2740173f)

        val restored = converters.stringToFloatList(converters.floatListToString(original))

        assertEquals(2, restored.size)
        assertEquals(original[0], restored[0], 1e-6f)
        assertEquals(original[1], restored[1], 1e-6f)
    }

    @Test
    fun threeClassProbabilitiesRoundTrip() {
        val original = listOf(0.2824f, 0.6754f, 0.0422f)

        val restored = converters.stringToFloatList(converters.floatListToString(original))

        assertEquals(original, restored)
    }

    @Test
    fun emptyAndNullBecomeAnEmptyList() {
        assertTrue(converters.stringToFloatList("").isEmpty())
        assertTrue(converters.stringToFloatList(null).isEmpty())
        assertTrue(converters.stringToFloatList("   ").isEmpty())
        assertEquals("", converters.floatListToString(null))
        assertEquals("", converters.floatListToString(emptyList()))
    }

    /** A malformed column must not throw and take the whole history query down with it. */
    @Test
    fun unparseableEntriesAreSkippedRatherThanThrowing() {
        val restored = converters.stringToFloatList("0.5,notANumber,0.25")

        assertEquals(listOf(0.5f, 0.25f), restored)
    }

    @Test
    fun storedFormIsReadableInADatabaseInspector() {
        assertEquals("0.25,0.75", converters.floatListToString(listOf(0.25f, 0.75f)))
    }
}

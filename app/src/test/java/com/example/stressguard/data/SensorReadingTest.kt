package com.example.stressguard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two different judgements live here and must not be conflated.
 *
 * *Implausible* readings are sensor faults or parse errors and are dropped, because feeding one
 * to the model yields a confident, meaningless prediction.
 *
 * *Out-of-training-range* readings are real people the model has not seen. They are kept and
 * flagged: live wearable heart rate exceeds the training maximum during ordinary activity, so
 * rejecting them would mean the app going silent exactly when someone is exerting themselves.
 */
class SensorReadingTest {

    private val elapsed = 1_000L
    private val epoch = 1_700_000_000_000L

    private fun reading(heartRate: Int, steps: Int) =
        SensorReading.from(heartRate, steps, elapsed, epoch)

    @Test
    fun typicalReadingIsAcceptedAndNotFlagged() {
        val result = reading(72, 6000)

        assertNotNull(result)
        assertEquals(72, result!!.heartRate)
        assertEquals(6000, result.dailySteps)
        assertEquals(elapsed, result.receivedAtElapsedMs)
        assertEquals(epoch, result.receivedAtEpochMs)
        assertFalse(result.outOfTrainingRange)
    }

    @Test
    fun impossibleHeartRateIsRejected() {
        assertNull("a stopped heart is a sensor fault", reading(0, 6000))
        assertNull("29 bpm is below the plausible floor", reading(29, 6000))
        assertNull("221 bpm is above the plausible ceiling", reading(221, 6000))
        assertNull("negative is nonsense", reading(-5, 6000))
    }

    @Test
    fun negativeStepsAreRejected() {
        assertNull(reading(72, -1))
    }

    /** Physiologically real, but beyond what the model was fit on. Kept, and marked. */
    @Test
    fun heartRateAboveTheTrainedRangeIsFlaggedNotRejected() {
        val result = reading(150, 6000)

        assertNotNull("150 bpm is a person exercising, not a fault", result)
        assertTrue(result!!.outOfTrainingRange)
    }

    @Test
    fun bothEdgesOfTheTrainedRangeCountAsInside() {
        assertFalse(reading(SensorReading.TRAINED_HEART_RATE.first, 6000)!!.outOfTrainingRange)
        assertFalse(reading(SensorReading.TRAINED_HEART_RATE.last, 6000)!!.outOfTrainingRange)
        assertFalse(reading(72, SensorReading.TRAINED_STEPS.first)!!.outOfTrainingRange)
        assertFalse(reading(72, SensorReading.TRAINED_STEPS.last)!!.outOfTrainingRange)
    }

    @Test
    fun stepCountJustOutsideTheTrainedRangeIsFlagged() {
        assertTrue(reading(72, SensorReading.TRAINED_STEPS.first - 1)!!.outOfTrainingRange)
        assertTrue(reading(72, SensorReading.TRAINED_STEPS.last + 1)!!.outOfTrainingRange)
    }

    @Test
    fun payloadFromTheWatchIsParsed() {
        val result = SensorReading.parse("88|4200", elapsed, epoch)

        assertNotNull(result)
        assertEquals(88, result!!.heartRate)
        assertEquals(4200, result.dailySteps)
    }

    @Test
    fun malformedPayloadsAreDroppedRatherThanGuessedAt() {
        assertNull("no separator", SensorReading.parse("88", elapsed, epoch))
        assertNull("empty", SensorReading.parse("", elapsed, epoch))
        assertNull("non-numeric heart rate", SensorReading.parse("abc|4200", elapsed, epoch))
        assertNull("non-numeric steps", SensorReading.parse("88|xyz", elapsed, epoch))
        assertNull("truncated", SensorReading.parse("88|", elapsed, epoch))
    }

    /** A partially decrypted message can yield a number that parses but is not a heart rate. */
    @Test
    fun payloadThatParsesButIsImplausibleIsStillDropped() {
        assertNull(SensorReading.parse("99999|4200", elapsed, epoch))
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        val result = SensorReading.parse(" 88 | 4200 ", elapsed, epoch)

        assertNotNull(result)
        assertEquals(88, result!!.heartRate)
    }

    /** Extra fields would mean a newer watch build; the first two are still valid. */
    @Test
    fun extraFieldsInThePayloadAreIgnored() {
        val result = SensorReading.parse("88|4200|somethingNew", elapsed, epoch)

        assertNotNull(result)
        assertEquals(88, result!!.heartRate)
        assertEquals(4200, result.dailySteps)
    }
}

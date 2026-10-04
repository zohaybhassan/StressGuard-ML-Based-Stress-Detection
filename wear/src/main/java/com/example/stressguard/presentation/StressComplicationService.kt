package com.example.stressguard.presentation

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/** Optional watch-face data source for the latest phone-computed stress percentage. */
class StressComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        complication(type, score = 36, label = "NORMAL")

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val state = WatchStateStore.state(applicationContext).value.state
        return complication(request.complicationType, state.score, state.label)
    }

    private fun complication(type: ComplicationType, score: Int?, label: String): ComplicationData? {
        val text = PlainComplicationText.Builder(score?.let { "$it%" } ?: "--").build()
        val description = PlainComplicationText.Builder(
            if (score == null) "Stress result unavailable" else "Stress $score percent, $label"
        ).build()
        val tapAction = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = (score ?: 0).toFloat(),
                min = 0f,
                max = 100f,
                contentDescription = description,
            )
                .setText(text)
                .setTapAction(tapAction)
                .build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = text,
                contentDescription = description,
            )
                .setTitle(PlainComplicationText.Builder("Stress").build())
                .setTapAction(tapAction)
                .build()
            else -> null
        }
    }
}

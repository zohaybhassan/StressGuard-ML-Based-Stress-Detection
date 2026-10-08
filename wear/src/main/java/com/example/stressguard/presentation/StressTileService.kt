package com.example.stressguard.presentation

import android.content.ComponentName
import androidx.wear.protolayout.ActionBuilders.launchAction
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography.DISPLAY_LARGE
import androidx.wear.protolayout.material3.Typography.LABEL_MEDIUM
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.tile

/** Glanceable system surface. The score is cached locally; it never runs inference on the watch. */
class StressTileService : Material3TileService(allowDynamicTheme = true) {
    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        val state = WatchStateStore.state(applicationContext).value.state
        val now = System.currentTimeMillis()
        val vitals = PassiveVitalsStore(applicationContext)
        val value = state.score?.let { "$it%" } ?: "--"
        val label = when (state.status) {
            WatchProtocol.Status.WORKOUT -> "Workout mode"
            WatchProtocol.Status.PROFILE_NEEDED -> "Finish setup on phone"
            WatchProtocol.Status.MODEL_ERROR -> "Phone model error"
            WatchProtocol.Status.WAITING -> "Waiting for phone"
            WatchProtocol.Status.READY -> state.label
        }
        val resultAge = if (state.measuredAtEpochMs <= 0L) {
            "no result"
        } else {
            val minutes = (now - state.measuredAtEpochMs).coerceAtLeast(0L) / 60_000L
            if (minutes < 1L) "now" else "${minutes}m ago"
        }
        val heartRate = vitals.recentHeartRate(now)?.let { "$it bpm" } ?: "HR --"
        val steps = vitals.stepsToday(now)
        val openApp = clickable(
            action = launchAction(ComponentName(this@StressTileService, MainActivity::class.java)),
            id = "open_stressguard",
        )
        return tile(
            timeline = Timeline.fromLayoutElement(
                primaryLayout(
                    titleSlot = {
                        text("$heartRate · $steps steps".layoutString, typography = LABEL_MEDIUM)
                    },
                    mainSlot = { text(value.layoutString, typography = DISPLAY_LARGE) },
                    labelForBottomSlot = {
                        text("$label · $resultAge".layoutString, typography = LABEL_MEDIUM)
                    },
                    bottomSlot = {
                        textEdgeButton(onClick = openApp) { text("Open".layoutString) }
                    },
                )
            )
        )
    }
}

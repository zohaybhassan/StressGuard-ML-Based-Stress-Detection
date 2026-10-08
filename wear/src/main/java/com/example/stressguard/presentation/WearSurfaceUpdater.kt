package com.example.stressguard.presentation

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

object WearSurfaceUpdater {
    private const val TAG = "WEAR_SURFACES"

    fun request(context: Context) {
        runCatching {
            TileService.getUpdater(context).requestUpdate(StressTileService::class.java)
        }.onFailure { Log.w(TAG, "could not refresh the stress tile", it) }
        runCatching {
            ComplicationDataSourceUpdateRequester.create(
                context,
                ComponentName(context, StressComplicationService::class.java),
            ).requestUpdateAll()
        }.onFailure { Log.w(TAG, "could not refresh stress complications", it) }
    }
}

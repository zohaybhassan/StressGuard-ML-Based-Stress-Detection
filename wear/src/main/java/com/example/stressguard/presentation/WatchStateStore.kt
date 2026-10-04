package com.example.stressguard.presentation

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ReceivedWatchState(
    val state: WatchProtocol.State = WatchProtocol.State(),
    val receivedAtEpochMs: Long = 0L,
)

/** Small durable cache so the last phone-owned result survives either app process restarting. */
object WatchStateStore {
    private const val PREFS = "stressguard_watch_state"
    private const val KEY_PAYLOAD = "payload"
    private const val KEY_RECEIVED_AT = "received_at"

    private val mutable = MutableStateFlow(ReceivedWatchState())
    private var initialized = false

    @Synchronized
    fun state(context: Context): StateFlow<ReceivedWatchState> {
        if (!initialized) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val decoded = prefs.getString(KEY_PAYLOAD, null)?.let(WatchProtocol.State::decode)
            mutable.value = ReceivedWatchState(
                state = decoded ?: WatchProtocol.State(),
                receivedAtEpochMs = prefs.getLong(KEY_RECEIVED_AT, 0L),
            )
            initialized = true
        }
        return mutable.asStateFlow()
    }

    fun update(context: Context, payload: String, receivedAtEpochMs: Long): Boolean {
        val decoded = WatchProtocol.State.decode(payload) ?: return false
        state(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PAYLOAD, payload)
            .putLong(KEY_RECEIVED_AT, receivedAtEpochMs)
            .apply()
        mutable.value = ReceivedWatchState(decoded, receivedAtEpochMs)
        WearSurfaceUpdater.request(context.applicationContext)
        return true
    }
}

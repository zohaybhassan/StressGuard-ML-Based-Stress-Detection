package com.example.stressguard.presentation

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WatchCommandClient {
    private const val TAG = "WATCH_COMMAND"
    private const val PREFS = "stressguard_watch_commands"
    private const val KEY_PENDING_FEEDBACK = "pending_feedback"

    suspend fun send(context: Context, command: WatchProtocol.Command): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                if (nodes.isEmpty()) {
                    if (command is WatchProtocol.Command.SaveFeedback) queueFeedback(context, command)
                    return@runCatching false
                }

                // Drain a response that was saved while the phone was unreachable before the new
                // command. A single slot is intentional: an alert can only have one response.
                pendingFeedback(context)?.let { pending ->
                    sendToNodes(context, nodes.map { it.id }, pending)
                    clearPendingFeedback(context)
                }
                sendToNodes(context, nodes.map { it.id }, command.encode())
                if (command is WatchProtocol.Command.SaveFeedback) clearPendingFeedback(context)
                true
            }.onFailure {
                if (command is WatchProtocol.Command.SaveFeedback) queueFeedback(context, command)
                Log.w(TAG, "could not send command to phone", it)
            }.getOrDefault(false)
        }

    fun hasPendingFeedback(context: Context): Boolean = pendingFeedback(context) != null

    private fun sendToNodes(context: Context, nodeIds: List<String>, commandPayload: String) {
        val payload = EncryptionUtil.encrypt(commandPayload)
        nodeIds.forEach { nodeId ->
            Tasks.await(
                Wearable.getMessageClient(context)
                    .sendMessage(nodeId, WatchProtocol.COMMAND_PATH, payload)
            )
        }
    }

    private fun queueFeedback(context: Context, command: WatchProtocol.Command.SaveFeedback) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_FEEDBACK, command.encode())
            .apply()
    }

    private fun pendingFeedback(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_FEEDBACK, null)
            ?.takeIf { it.isNotBlank() }

    private fun clearPendingFeedback(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PENDING_FEEDBACK)
            .apply()
    }
}

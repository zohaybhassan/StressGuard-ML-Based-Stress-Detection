package com.example.stressguard

/** Compact, versioned messages used between the phone and Wear OS app. */
object WatchProtocol {
    const val STATE_PATH = "/stress_state"
    const val COMMAND_PATH = "/stress_command"
    private const val VERSION = 1

    enum class Status {
        WAITING,
        READY,
        WORKOUT,
        PROFILE_NEEDED,
        MODEL_ERROR,
    }

    enum class WorkoutStatus {
        NONE,
        ACTIVE,
        PAUSED,
    }

    data class State(
        val status: Status,
        val score: Int? = null,
        val label: String = "WAITING",
        val severity: StressSeverity = StressSeverity.UNKNOWN,
        val measuredAtEpochMs: Long = 0L,
        val outOfTrainingRange: Boolean = false,
        val alertsMutedUntilEpochMs: Long = 0L,
        val workoutUntilEpochMs: Long = 0L,
        val workoutStatus: WorkoutStatus = WorkoutStatus.NONE,
        val feedbackId: Long = 0L,
        val alertActive: Boolean = false,
    ) {
        fun encode(): String = listOf(
            VERSION,
            status.name,
            score ?: -1,
            sanitize(label),
            severity.name,
            measuredAtEpochMs,
            if (outOfTrainingRange) 1 else 0,
            alertsMutedUntilEpochMs,
            workoutUntilEpochMs,
            workoutStatus.name,
            feedbackId,
            if (alertActive) 1 else 0,
        ).joinToString("|")

        companion object {
            fun decode(payload: String): State? {
                val parts = payload.split('|')
                if (parts.size != 12 || parts[0].toIntOrNull() != VERSION) return null
                return runCatching {
                    State(
                        status = Status.valueOf(parts[1]),
                        score = parts[2].toInt().takeIf { it >= 0 }?.coerceIn(0, 100),
                        label = parts[3].ifBlank { "WAITING" },
                        severity = StressSeverity.valueOf(parts[4]),
                        measuredAtEpochMs = parts[5].toLong().coerceAtLeast(0L),
                        outOfTrainingRange = parts[6] == "1",
                        alertsMutedUntilEpochMs = parts[7].toLong().coerceAtLeast(0L),
                        workoutUntilEpochMs = parts[8].toLong().coerceAtLeast(0L),
                        workoutStatus = WorkoutStatus.valueOf(parts[9]),
                        feedbackId = parts[10].toLong().coerceAtLeast(0L),
                        alertActive = parts[11] == "1",
                    )
                }.getOrNull()
            }
        }
    }

    sealed interface Command {
        data object RequestState : Command
        data class MuteAlerts(val durationMs: Long) : Command
        data object ResumeAlerts : Command
        data class StartWorkout(val durationMs: Long) : Command
        data object PauseWorkout : Command
        data object ResumeWorkout : Command
        data object EndWorkout : Command
        data class SaveFeedback(
            val feedbackId: Long,
            val confirmedStressed: Boolean,
            val severity: Int?,
        ) : Command

        fun encode(): String = when (this) {
            RequestState -> "$VERSION|REQUEST_STATE"
            is MuteAlerts -> "$VERSION|MUTE_ALERTS|$durationMs"
            ResumeAlerts -> "$VERSION|RESUME_ALERTS"
            is StartWorkout -> "$VERSION|START_WORKOUT|$durationMs"
            PauseWorkout -> "$VERSION|PAUSE_WORKOUT"
            ResumeWorkout -> "$VERSION|RESUME_WORKOUT"
            EndWorkout -> "$VERSION|END_WORKOUT"
            is SaveFeedback -> listOf(
                VERSION,
                "SAVE_FEEDBACK",
                feedbackId,
                if (confirmedStressed) 1 else 0,
                severity ?: 0,
            ).joinToString("|")
        }

        companion object {
            fun decode(payload: String): Command? {
                val parts = payload.split('|')
                if (parts.size < 2 || parts[0].toIntOrNull() != VERSION) return null
                return runCatching {
                    when (parts[1]) {
                        "REQUEST_STATE" -> RequestState
                        "MUTE_ALERTS" -> MuteAlerts(parts[2].toLong())
                        "RESUME_ALERTS" -> ResumeAlerts
                        "START_WORKOUT" -> StartWorkout(parts[2].toLong())
                        "PAUSE_WORKOUT" -> PauseWorkout
                        "RESUME_WORKOUT" -> ResumeWorkout
                        "END_WORKOUT" -> EndWorkout
                        "SAVE_FEEDBACK" -> SaveFeedback(
                            feedbackId = parts[2].toLong(),
                            confirmedStressed = parts[3] == "1",
                            severity = parts[4].toInt().takeIf { it in 1..10 },
                        )
                        else -> null
                    }
                }.getOrNull()
            }
        }
    }

    private fun sanitize(value: String): String = value.replace('|', ' ').take(32)
}

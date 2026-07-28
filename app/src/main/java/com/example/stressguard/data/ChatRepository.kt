package com.example.stressguard.data

import android.util.Log
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/** Who said it. Constrained rather than free text, because it decides how a bubble is drawn. */
enum class ChatRole { USER, ASSISTANT }

/** One line of conversation, as the screen needs it. */
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    /** True when the assistant's reply came from the safety layer rather than the model. */
    val isFallback: Boolean = false,
    val atEpochMs: Long = System.currentTimeMillis(),
)

/** What the Edge Function sends back. */
@Serializable
data class ChatReply(
    val reply: String,
    val isFallback: Boolean = false,
    /** True when the crisis check fired, so the session can be marked. */
    val isCrisis: Boolean = false,
)

@Serializable
private data class ChatRequest(
    val message: String,
    val history: List<Turn>,
    /** Null when nothing has been predicted yet, or the profile is incomplete. */
    val context: StressContext? = null,
) {
    @Serializable
    data class Turn(val role: String, val content: String)
}

/**
 * What the assistant is told about the person it is talking to.
 *
 * This leaves the device and reaches Hugging Face inside the prompt. That is a deliberate choice
 * and worth stating: the alternative — describing the readings only in relative terms — keeps the
 * numbers off a third party's servers, and was rejected in favour of an assistant that can speak
 * concretely. The disclaimer on the assistant screen says so.
 *
 * Attribution is included, not just the values, because "your heart rate is 92" invites the model
 * to interpret a number clinically, whereas "heart rate is what the model weighted most heavily"
 * is a statement about the model rather than about the person's health.
 */
@Serializable
data class StressContext(
    val label: String,
    @SerialName("heart_rate") val heartRate: Int,
    @SerialName("daily_steps") val dailySteps: Int,
    @SerialName("sleep_hours") val sleepHours: Float,
    /** Human-readable, e.g. "heart rate, well above typical". Null when nothing pushed upward. */
    @SerialName("main_driver") val mainDriver: String? = null,
    /** Every live input with its standing, so the model can be specific without inventing. */
    val drivers: List<String> = emptyList(),
    /** True when the unchangeable profile outweighed everything measured today. */
    @SerialName("profile_dominates") val profileDominates: Boolean = false,
    /** The reading sat outside the model's trained ranges, so it extrapolated. */
    val extrapolating: Boolean = false,
) {
    companion object {
        fun from(explanation: StressExplanation): StressContext {
            val vitals = explanation.drivers.associateBy { it.feature }
            return StressContext(
                label = explanation.label,
                heartRate = vitals[LiveFeature.HEART_RATE]?.observed?.toInt() ?: 0,
                dailySteps = vitals[LiveFeature.DAILY_STEPS]?.observed?.toInt() ?: 0,
                sleepHours = vitals[LiveFeature.SLEEP]?.observed ?: 0f,
                mainDriver = explanation.leadingDriver?.let { "${it.feature.plain()}, ${it.deviation}" },
                drivers = explanation.drivers.map { "${it.feature.plain()}: ${it.deviation}" },
                profileDominates = explanation.profileDominates,
                extrapolating = explanation.extrapolating,
            )
        }
    }
}

private fun LiveFeature.plain(): String = when (this) {
    LiveFeature.HEART_RATE -> "heart rate"
    LiveFeature.DAILY_STEPS -> "daily activity"
    LiveFeature.SLEEP -> "sleep"
}

@Serializable
private data class ChatSessionRow(
    val id: String? = null,
    @SerialName("user_id") val userId: String,
    @SerialName("stress_at_start") val stressAtStart: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("crisis_fallback_fired") val crisisFallbackFired: Boolean = false,
)

@Serializable
private data class ChatMessageRow(
    @SerialName("session_id") val sessionId: String,
    @SerialName("user_id") val userId: String,
    val role: String,
    val content: String,
    @SerialName("is_fallback") val isFallback: Boolean = false,
    @SerialName("created_at") val createdAt: String,
)

/**
 * The supportive chatbot, plan §18.
 *
 * Every reply comes from a Supabase Edge Function, never from Hugging Face directly. That is not a
 * layering preference: the API token would otherwise have to ship inside the APK, which is a zip
 * file anyone can unpack, and §18's exit criteria test for exactly that. The safety prompt and the
 * crisis check live there for the same reason — on the client they could be edited out.
 *
 * Transcripts are stored in Supabase behind RLS. Sending a message writes both sides, so a reply
 * the user saw is a reply the record shows. Storage failures are logged and swallowed: losing the
 * archive is bad, but dropping a conversation someone is in the middle of is worse.
 */
object ChatRepository {

    private const val TAG = "CHAT"
    private const val FUNCTION = "chat"
    private const val SESSIONS = "chat_sessions"
    private const val MESSAGES = "chat_messages"

    /** How much conversation is sent back for context; the function bounds this again server-side. */
    private const val HISTORY_TURNS = 12

    /**
     * The session to append to, creating one if there is no open conversation.
     *
     * Resuming rather than always starting fresh is why full transcripts were worth storing: the
     * assistant remembers what was said this morning instead of meeting the user as a stranger
     * every time they open the tab.
     */
    suspend fun openSession(stressAtStart: String?): String? {
        val userId = AuthRepository.currentUser?.id ?: run {
            Log.w(TAG, "not signed in; conversation cannot be stored")
            return null
        }

        return runCatching {
            val existing = SupabaseProvider.client.from(SESSIONS)
                .select {
                    filter {
                        eq("user_id", userId)
                        filter("ended_at", io.github.jan.supabase.postgrest.query.filter.FilterOperator.IS, "null")
                    }
                    order("started_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                    limit(1)
                }
                .decodeList<ChatSessionRow>()
                .firstOrNull()

            existing?.id ?: SupabaseProvider.client.from(SESSIONS)
                .insert(ChatSessionRow(userId = userId, stressAtStart = stressAtStart)) {
                    select()
                }
                .decodeSingle<ChatSessionRow>()
                .id
        }
            .onFailure { Log.w(TAG, "could not open a chat session; continuing unstored", it) }
            .getOrNull()
    }

    /** Everything said in [sessionId], oldest first. */
    suspend fun history(sessionId: String): List<ChatMessage> = runCatching {
        SupabaseProvider.client.from(MESSAGES)
            .select {
                filter { eq("session_id", sessionId) }
                order("created_at", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
            }
            .decodeList<ChatMessageRow>()
            .map { row ->
                ChatMessage(
                    role = if (row.role == "user") ChatRole.USER else ChatRole.ASSISTANT,
                    content = row.content,
                    isFallback = row.isFallback,
                    atEpochMs = runCatching { Instant.parse(row.createdAt).toEpochMilli() }
                        .getOrDefault(System.currentTimeMillis()),
                )
            }
    }
        .onFailure { Log.w(TAG, "could not load the conversation", it) }
        .getOrDefault(emptyList())

    /**
     * Sends a message and returns the assistant's reply.
     *
     * Throws nothing: the Edge Function answers with a usable fallback for every failure it can
     * see, and anything it cannot see is turned into one here. A person who opened this screen
     * because they were stressed should never be shown a stack trace.
     */
    suspend fun send(
        sessionId: String?,
        message: String,
        history: List<ChatMessage>,
        context: StressContext? = null,
    ): ChatReply {
        val reply = runCatching { invoke(message, history, context) }
            .onFailure { Log.w(TAG, "chat function call failed", it) }
            .getOrElse {
                // The server normally owns the crisis check, but it cannot answer if it cannot be
                // reached, and a crisis does not wait for signal. Offering breathing exercises to
                // someone who has just written "I want to die" is the worst thing this app could
                // say, so the offline path checks before it falls back.
                if (CrisisCheck.isCrisis(message)) {
                    ChatReply(reply = CrisisCheck.REPLY, isFallback = true, isCrisis = true)
                } else {
                    ChatReply(reply = OFFLINE_REPLY, isFallback = true)
                }
            }

        if (sessionId != null) {
            store(sessionId, ChatMessage(ChatRole.USER, message))
            store(sessionId, ChatMessage(ChatRole.ASSISTANT, reply.reply, reply.isFallback))
            if (reply.isCrisis) markCrisis(sessionId)
        }
        return reply
    }

    private suspend fun invoke(
        message: String,
        history: List<ChatMessage>,
        context: StressContext?,
    ): ChatReply {
        val response: HttpResponse = SupabaseProvider.client.functions.invoke(FUNCTION) {
            contentType(ContentType.Application.Json)
            setBody(
                ChatRequest(
                    message = message,
                    history = history.takeLast(HISTORY_TURNS).map {
                        ChatRequest.Turn(
                            role = if (it.role == ChatRole.USER) "user" else "assistant",
                            content = it.content,
                        )
                    },
                    context = context,
                )
            )
        }
        return response.body()
    }

    private suspend fun store(sessionId: String, message: ChatMessage) {
        val userId = AuthRepository.currentUser?.id ?: return
        runCatching {
            SupabaseProvider.client.from(MESSAGES).insert(
                ChatMessageRow(
                    sessionId = sessionId,
                    userId = userId,
                    role = if (message.role == ChatRole.USER) "user" else "assistant",
                    content = message.content,
                    isFallback = message.isFallback,
                    // Set here rather than by the database default, because the unique key is
                    // (session_id, created_at) and two rows written in the same request must not
                    // collide on a server clock that has not moved between them.
                    createdAt = Instant.ofEpochMilli(message.atEpochMs).toString(),
                )
            )
        }.onFailure { Log.w(TAG, "could not store a message; the conversation is unaffected", it) }
    }

    /** Records that the safety path fired, so it can be audited without reading the messages. */
    private suspend fun markCrisis(sessionId: String) {
        runCatching {
            SupabaseProvider.client.from(SESSIONS).update({
                set("crisis_fallback_fired", true)
            }) {
                filter { eq("id", sessionId) }
            }
        }.onFailure { Log.w(TAG, "could not flag the crisis fallback on the session", it) }
    }

    /** Closes the conversation so the next one starts fresh. */
    suspend fun endSession(sessionId: String) {
        runCatching {
            SupabaseProvider.client.from(SESSIONS).update({
                set("ended_at", Instant.now().toString())
            }) {
                filter { eq("id", sessionId) }
            }
        }.onFailure { Log.w(TAG, "could not close the session", it) }
    }

    /**
     * Shown when the function cannot be reached at all — no network, or Supabase unreachable.
     *
     * Offers something that works without a server rather than an error, because the breathing
     * pattern is the part of this feature that never needed one.
     */
    private const val OFFLINE_REPLY =
        "I can't reach the network right now, so I'm limited — but I'm still here.\n\n" +
            "Try this while you wait: breathe in for four counts, hold for four, out for six. " +
            "Four rounds. The longer out-breath is what does the work.\n\n" +
            "Message me again when you have a connection."
}

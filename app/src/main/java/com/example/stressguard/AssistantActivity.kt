package com.example.stressguard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.stressguard.data.ChatMessage
import com.example.stressguard.data.ChatRepository
import com.example.stressguard.data.ChatRole
import com.example.stressguard.data.StressContext
import com.example.stressguard.data.StressPipeline
import com.example.stressguard.ui.fitSystemBars
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * The supportive chatbot, plan §18.
 *
 * Deliberately thin. Every decision that matters — the system prompt, the crisis check, the model,
 * the fallbacks — lives in the Supabase Edge Function, because on the client all of it could be
 * edited out by anyone with the APK. What is left here is a list, a text box, and the discipline
 * to never invent a reply of its own.
 */
class AssistantActivity : AppCompatActivity() {

    private lateinit var rvMessages: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: MaterialButton
    private lateinit var tvTyping: TextView

    private val adapter = ChatAdapter()

    /** Null when signed out or Supabase is unreachable; the conversation still works, unstored. */
    private var sessionId: String? = null

    /**
     * What the app currently reads about the user, refreshed when the screen opens.
     *
     * Computed once here rather than per message: attribution costs four inferences, and the
     * reading will not have changed between two lines of the same conversation. Null until it
     * arrives, and null forever if nothing has been predicted yet — in which case the assistant
     * simply talks without it.
     */
    private var stressContext: StressContext? = null

    /** Guards against a second send while one is in flight, which would interleave the history. */
    private var awaitingReply = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assistant)

        rvMessages = findViewById(R.id.rvMessages)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        tvTyping = findViewById(R.id.tvTyping)

        rvMessages.layoutManager = LinearLayoutManager(this).apply {
            // New messages appear at the bottom and the view follows them, which is what a
            // conversation is expected to do.
            stackFromEnd = true
        }
        rvMessages.adapter = adapter

        findViewById<MaterialToolbar>(R.id.topAppBar).apply {
            inflateMenu(R.menu.assistant_menu)
            setNavigationOnClickListener { finish() }
            setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.action_new_conversation) {
                    startFreshConversation()
                    true
                } else {
                    false
                }
            }
        }

        BottomNav.wire(this, findViewById<BottomNavigationView>(R.id.bottomNavigation), R.id.nav_assistant)
        // The only screen that asks the keyboard to be accounted for: the composer has to stay
        // reachable while typing, and from API 35 the platform no longer resizes the window to
        // make that happen on its own.
        fitSystemBars(
            top = findViewById(R.id.assistantRoot),
            bottom = findViewById(R.id.bottomNavigation),
            bottomFollowsKeyboard = true,
        )

        btnSend.setOnClickListener { sendCurrentMessage() }
        etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentMessage()
                true
            } else {
                false
            }
        }

        restoreConversation()
    }

    /**
     * Opens the stored conversation, or greets the user if there is none.
     *
     * The greeting is written here rather than asked of the model: it costs a network round trip
     * to say something entirely predictable, and an empty screen is a poor thing to show someone
     * who tapped "Feeling overwhelmed".
     */
    private fun restoreConversation() {
        lifecycleScope.launch {
            // Started first and not waited on: the conversation must open immediately, and the
            // reading only has to arrive before the user finishes typing their first message.
            launch { stressContext = loadStressContext() }

            sessionId = ChatRepository.openSession(stressAtStart = intent.getStringExtra(EXTRA_STRESS))

            val stored = sessionId?.let { ChatRepository.history(it) }.orEmpty()
            if (stored.isNotEmpty()) {
                adapter.replaceAll(stored)
                rvMessages.scrollToPosition(adapter.itemCount - 1)
                return@launch
            }

            adapter.add(ChatMessage(ChatRole.ASSISTANT, greeting()))
            rvMessages.scrollToPosition(adapter.itemCount - 1)
        }
    }

    /**
     * Asks the pipeline which input drove the last prediction.
     *
     * Off the main thread and off the real-time path: this runs four extra inferences, which is
     * fine once when a screen opens and would be indefensible between a reading arriving and an
     * alert firing.
     */
    private suspend fun loadStressContext(): StressContext? =
        StressPipeline.get(applicationContext).explainLatest()?.let { StressContext.from(it) }

    /** Acknowledges why the user is here when the alert sent them, and stays neutral otherwise. */
    private fun greeting(): String =
        if (intent.getBooleanExtra(EXTRA_FROM_ALERT, false)) {
            "Your readings have been high for a little while. Do you want to talk about what's " +
                "going on, or would something calming be more useful right now?"
        } else {
            "Hi. I'm here if you want to talk through whatever is on your mind."
        }

    private fun sendCurrentMessage() {
        val text = etMessage.text.toString().trim()
        if (text.isEmpty() || awaitingReply) return

        etMessage.setText("")
        rvMessages.scrollToPosition(adapter.add(ChatMessage(ChatRole.USER, text)))

        // History is captured before the reply is requested, so the message just sent is not
        // duplicated into the context the function receives.
        val history = adapter.snapshot().dropLast(1)
        setAwaitingReply(true)

        lifecycleScope.launch {
            val reply = ChatRepository.send(sessionId, text, history, stressContext)
            setAwaitingReply(false)
            rvMessages.scrollToPosition(
                adapter.add(ChatMessage(ChatRole.ASSISTANT, reply.reply, reply.isFallback))
            )
        }
    }

    private fun setAwaitingReply(waiting: Boolean) {
        awaitingReply = waiting
        btnSend.isEnabled = !waiting
        tvTyping.visibility = if (waiting) TextView.VISIBLE else TextView.GONE
    }

    /** Closes the stored conversation and starts an empty one. */
    private fun startFreshConversation() {
        lifecycleScope.launch {
            sessionId?.let { ChatRepository.endSession(it) }
            sessionId = ChatRepository.openSession(stressAtStart = null)
            adapter.replaceAll(listOf(ChatMessage(ChatRole.ASSISTANT, greeting())))
        }
    }

    companion object {
        private const val EXTRA_FROM_ALERT = "from_alert"
        private const val EXTRA_STRESS = "stress_at_start"

        /** Opened from a tab or a button, with no particular reason attached. */
        fun intent(context: Context) = Intent(context, AssistantActivity::class.java)

        /**
         * Opened from a high-stress alert, per plan §18's "quick action from high-stress alert".
         *
         * The reason travels with the intent so the conversation can open by acknowledging it,
         * and so the stored session records what the app believed when it began.
         */
        fun fromAlert(context: Context, stressLabel: String?) =
            Intent(context, AssistantActivity::class.java)
                .putExtra(EXTRA_FROM_ALERT, true)
                .putExtra(EXTRA_STRESS, stressLabel)
    }
}

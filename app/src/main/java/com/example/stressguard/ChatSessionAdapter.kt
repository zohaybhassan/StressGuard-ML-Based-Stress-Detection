package com.example.stressguard

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.stressguard.data.ChatSessionSummary
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.text.DateFormat
import java.util.Date

/** Rows in the Assistant's saved-conversation sheet. */
class ChatSessionAdapter(
    private val onOpen: (ChatSessionSummary) -> Unit,
    private val onDelete: (ChatSessionSummary) -> Unit,
) : RecyclerView.Adapter<ChatSessionAdapter.SessionViewHolder>() {

    private val sessions = mutableListOf<ChatSessionSummary>()
    private var activeSessionId: String? = null

    class SessionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.chatSessionCard)
        val title: TextView = view.findViewById(R.id.tvSessionTitle)
        val preview: TextView = view.findViewById(R.id.tvSessionPreview)
        val meta: TextView = view.findViewById(R.id.tvSessionMeta)
        val delete: MaterialButton = view.findViewById(R.id.btnDeleteSession)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = SessionViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_chat_session, parent, false)
    )

    override fun getItemCount(): Int = sessions.size

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        val session = sessions[position]
        val context = holder.itemView.context
        val isActive = session.id == activeSessionId

        holder.title.text = formatStartedAt(session.startedAtEpochMs)
        holder.preview.text = session.preview
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.chat_history_empty_preview)
        holder.meta.text = buildString {
            append(context.getString(R.string.chat_history_messages, session.messageCount))
            if (isActive) append(" · ").append(context.getString(R.string.chat_history_active))
            session.stressAtStart?.takeIf { it.isNotBlank() }?.let {
                append(" · ").append(StressDisplay.label(it))
            }
        }

        holder.card.strokeWidth = context.resources.getDimensionPixelSize(
            if (isActive) R.dimen.stroke_width_selected else R.dimen.stroke_width
        )
        holder.card.strokeColor = ContextCompat.getColor(
            context,
            if (isActive) R.color.brand else R.color.outline,
        )
        holder.card.setOnClickListener { onOpen(session) }
        holder.delete.setOnClickListener { onDelete(session) }
    }

    fun submit(items: List<ChatSessionSummary>, currentSessionId: String?) {
        val previous = sessions.toList()
        val previousActive = activeSessionId
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = previous.size
            override fun getNewListSize() = items.size
            override fun areItemsTheSame(oldPosition: Int, newPosition: Int) =
                previous[oldPosition].id == items[newPosition].id

            override fun areContentsTheSame(oldPosition: Int, newPosition: Int): Boolean {
                val old = previous[oldPosition]
                val new = items[newPosition]
                return old == new &&
                    (old.id == previousActive) == (new.id == currentSessionId)
            }
        })
        sessions.clear()
        sessions += items
        activeSessionId = currentSessionId
        diff.dispatchUpdatesTo(this)
    }

    fun remove(sessionId: String) {
        val position = sessions.indexOfFirst { it.id == sessionId }
        if (position < 0) return
        sessions.removeAt(position)
        notifyItemRemoved(position)
    }

    private fun formatStartedAt(epochMs: Long): String {
        if (epochMs <= 0L) return "Saved conversation"
        val date = Date(epochMs)
        return if (DateUtils.isToday(epochMs)) {
            "Today, ${DateFormat.getTimeInstance(DateFormat.SHORT).format(date)}"
        } else {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(date)
        }
    }
}

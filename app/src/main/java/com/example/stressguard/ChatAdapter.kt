package com.example.stressguard

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.stressguard.data.ChatMessage
import com.example.stressguard.data.ChatRole

/**
 * Renders the conversation.
 *
 * One view type for both speakers. The difference between a user bubble and an assistant bubble is
 * alignment and colour, and two layouts would have drifted apart the first time either was edited.
 *
 * A fallback reply is tinted differently from a model reply on purpose. When the assistant says
 * something because the network failed or because the crisis check fired, that is not the same
 * kind of statement as a generated one, and the transcript should not present them identically.
 */
class ChatAdapter(
    private val messages: MutableList<ChatMessage> = mutableListOf(),
) : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val row: FrameLayout = view.findViewById(R.id.bubbleRow)
        val bubble: TextView = view.findViewById(R.id.tvBubble)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = MessageViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
    )

    override fun getItemCount() = messages.size

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.bubble.text = message.content

        val fromUser = message.role == ChatRole.USER
        (holder.bubble.layoutParams as FrameLayout.LayoutParams).gravity =
            if (fromUser) Gravity.END else Gravity.START
        holder.row.requestLayout()

        val background = when {
            fromUser -> USER_BUBBLE
            // Safety and network replies are the app speaking, not the model. Marking them keeps
            // the transcript honest about which is which.
            message.isFallback -> FALLBACK_BUBBLE
            else -> ASSISTANT_BUBBLE
        }
        holder.bubble.background?.mutate()?.let {
            DrawableCompat.setTint(it, Color.parseColor(background))
        }
        holder.bubble.setTextColor(
            Color.parseColor(if (fromUser) USER_TEXT else ASSISTANT_TEXT)
        )
    }

    /** Appends one message and returns its position, for scrolling to it. */
    fun add(message: ChatMessage): Int {
        messages += message
        notifyItemInserted(messages.lastIndex)
        return messages.lastIndex
    }

    fun replaceAll(newMessages: List<ChatMessage>) {
        messages.clear()
        messages += newMessages
        notifyDataSetChanged()
    }

    /** A defensive copy, so the caller cannot mutate what the list is drawing. */
    fun snapshot(): List<ChatMessage> = messages.toList()

    val isEmpty: Boolean get() = messages.isEmpty()

    private companion object {
        const val USER_BUBBLE = "#0B57D0"
        const val USER_TEXT = "#FFFFFF"
        const val ASSISTANT_BUBBLE = "#FFFFFF"
        const val ASSISTANT_TEXT = "#101816"
        const val FALLBACK_BUBBLE = "#FFF3D6"
    }
}

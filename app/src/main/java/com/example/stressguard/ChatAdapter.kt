package com.example.stressguard

import android.graphics.Rect
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
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

        /**
         * The bubble's padding, captured before any background is swapped in.
         *
         * `setBackgroundResource` re-applies the new drawable's padding, which for a plain shape
         * is none — so without this the first rebind flattens the text against the bubble edge.
         */
        val padding = Rect(
            bubble.paddingLeft,
            bubble.paddingTop,
            bubble.paddingRight,
            bubble.paddingBottom,
        )
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

        @DrawableRes val background = when {
            fromUser -> R.drawable.bg_bubble_user
            // Safety and network replies are the app speaking, not the model. Marking them keeps
            // the transcript honest about which is which.
            message.isFallback -> R.drawable.bg_bubble_fallback
            else -> R.drawable.bg_bubble_assistant
        }

        @ColorRes val textColor = when {
            fromUser -> R.color.bubble_user_text
            message.isFallback -> R.color.bubble_fallback_text
            else -> R.color.bubble_assistant_text
        }

        // Three drawables rather than one tinted shape, because the speakers no longer differ only
        // in colour: each bubble squares off the corner nearest its sender, which is what makes
        // the conversation readable at a glance before any colour is taken in.
        holder.bubble.setBackgroundResource(background)
        holder.bubble.setPadding(
            holder.padding.left,
            holder.padding.top,
            holder.padding.right,
            holder.padding.bottom,
        )
        holder.bubble.setTextColor(ContextCompat.getColor(holder.itemView.context, textColor))
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
}

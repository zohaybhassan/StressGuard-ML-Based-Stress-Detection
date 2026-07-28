package com.example.stressguard.data

/**
 * A copy of the Edge Function's crisis check, used only when the function cannot be reached.
 *
 * The server is authoritative and this is deliberately not consulted while it is reachable. The
 * duplication exists to close one specific gap: with no network, `ChatRepository` falls back to a
 * breathing exercise, and telling someone who has just written "I want to die" to breathe in for
 * four counts is the worst thing this app could say. A crisis does not wait for signal.
 *
 * Kept small and phrase-identical to `supabase/functions/chat/safety.ts` on purpose. If either
 * side changes, change both — the alternative was a client with no answer at all for the one case
 * where having no answer is unacceptable.
 */
object CrisisCheck {

    /**
     * Deliberately over-matching. A false positive shows a helpline to someone who did not need
     * one; a false negative offers breathing exercises to someone in danger. The costs are not
     * remotely symmetrical.
     *
     * Phrases rather than single words for the same reason precision matters here at all: "kill"
     * alone fires on "this deadline is killing me", which is the ordinary stressed speech the
     * assistant exists to talk about.
     */
    private val PHRASES = listOf(
        "kill myself", "killing myself",
        "end my life", "ending my life", "take my own life",
        "want to die", "wanna die",
        "better off dead", "no reason to live", "nothing to live for", "not worth living",
        "suicide", "suicidal",
        "hurt myself", "hurting myself", "harm myself", "harming myself",
        "self harm", "selfharm", "cut myself", "cutting myself",
        "overdose", "end it all",
    )

    /**
     * The offline crisis reply.
     *
     * Shorter than the server's, and pointedly so: it says the app is offline, because promising
     * a conversation it cannot have would be its own kind of harm. The phone numbers work without
     * data.
     */
    const val REPLY: String =
        "I can't reach the network right now, and I'm an app rather than a person — so I'm not " +
            "able to help with this safely.\n\n" +
            "Please contact someone who can, right now. In Pakistan: Umang on 0311 7786264, or " +
            "Rozan on 0800 22444. If you are in immediate danger, call 1122. These work without " +
            "an internet connection.\n\n" +
            "If someone you trust is nearby, tell them how you're feeling."

    /** Strips the punctuation and casing of ordinary typing before matching. */
    fun normalise(message: String): String = message
        .lowercase()
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun isCrisis(message: String): Boolean {
        val text = normalise(message)
        return PHRASES.any { text.contains(it) }
    }
}

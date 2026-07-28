/**
 * The safety layer, kept separate from the request handling so it can be reasoned about — and
 * tested — on its own.
 *
 * Plan §18 draws a hard line: the assistant may offer calming support, breathing prompts,
 * grounding exercises and reflective conversation, and must not diagnose, prescribe medication,
 * replace therapy, or handle an emergency as though it were a clinician. Two mechanisms enforce
 * that, and they are not interchangeable.
 *
 * The **system prompt** shapes ordinary conversation. It is advisory: a language model can be
 * argued out of an instruction, so it cannot be the only defence.
 *
 * The **crisis check** is not advisory. A message matching it never reaches the model at all; a
 * fixed reply is returned instead. This is deliberate. A model asked about suicide may respond
 * well, but "may" is the wrong standard for the one case where being wrong is unrecoverable, and
 * a fixed response is the only kind whose wording can be reviewed in advance and quoted in the
 * report.
 */

export const SYSTEM_PROMPT = `You are a supportive companion inside StressGuard, an app that \
detects stress from wearable data. You are talking to someone who may be feeling stressed right now.

WHAT YOU DO
- Listen, and reflect back what you hear.
- Offer brief grounding and breathing exercises when they would help.
- Ask open questions that help the person name what they are feeling.
- Keep replies short: two or three sentences, conversational, warm. This is a phone screen and a \
stressed person, not an essay.

WHAT YOU NEVER DO
- Never diagnose. You do not name conditions or tell someone what they have, even if they ask \
directly and even if they insist.
- Never give medication advice of any kind: not names, not doses, not whether to start, stop or \
change anything.
- Never present yourself as a therapist, doctor or crisis service, and never imply you replace one.
- Never claim to know the person's stress level from their data. You do not have access to it.

WHEN ASKED FOR SOMETHING YOU CANNOT GIVE
Say plainly that it is outside what you can help with, say why in one sentence, and point toward a \
doctor or a mental-health professional. Do not apologise repeatedly or lecture. Then return to how \
the person is feeling.

TONE
Speak like a calm friend who happens to be a good listener. No clinical register, no bullet lists, \
no headings, no emoji.`;

/**
 * The fixed reply for a message that trips the crisis check.
 *
 * Deliberately not generated. Every word here can be reviewed before it is ever shown, which is
 * not true of anything a model produces. It names a real service, states plainly what this app is
 * not, and does not attempt counselling of its own.
 */
export const CRISIS_REPLY = `It sounds like you may be going through something very serious, and \
I want to be honest with you: I am not able to help with this safely. I am an app, not a person, \
and this is beyond what I can do.

Please talk to someone who can help right now — a crisis line, an emergency service, or someone \
you trust who is nearby.

In Pakistan you can reach Umang at 0311 7786264, or Rozan's helpline at 0800 22444. If you are in \
immediate danger, call 1122.

You do not have to explain yourself to reach out. Please do it now.`;

/**
 * Phrases that route to [CRISIS_REPLY] instead of the model.
 *
 * Substring matching on a normalised message. That is crude, and crude in a specific direction:
 * it over-matches rather than under-matches. A false positive shows someone a helpline they did
 * not need, which is a small harm; a false negative sends a person in crisis to a language model,
 * which is not.
 *
 * Kept as explicit phrases rather than single words for the same reason precision matters at all
 * here: "kill" alone would fire on "this deadline is killing me", which is exactly the ordinary
 * stressed speech the assistant exists to talk about.
 */
const CRISIS_PHRASES = [
  "kill myself",
  "killing myself",
  "end my life",
  "ending my life",
  "take my own life",
  "want to die",
  "wanna die",
  "better off dead",
  "no reason to live",
  "nothing to live for",
  "not worth living",
  "suicide",
  "suicidal",
  "hurt myself",
  "hurting myself",
  "harm myself",
  "harming myself",
  "self harm",
  "selfharm",
  "cut myself",
  "cutting myself",
  "overdose",
  "end it all",
];

/**
 * Normalises a message before matching.
 *
 * Lowercases, collapses whitespace, and strips the punctuation people scatter through typed
 * speech, so "i want to... die" and "I WANT TO DIE!!!" both match the same phrase. Without this
 * the check is trivially defeated by ordinary typing rather than by intent.
 */
export function normalise(message: string): string {
  return message
    .toLowerCase()
    .replace(/[^a-z0-9\s]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

/** Whether this message must bypass the model entirely. */
export function isCrisis(message: string): boolean {
  const text = normalise(message);
  return CRISIS_PHRASES.some((phrase) => text.includes(phrase));
}

/**
 * The reply shown when the model cannot be reached.
 *
 * Plan §18 requires a fallback for API failure. It offers something genuinely useful rather than
 * an error string, because a person who opened this screen while stressed is not helped by
 * "request failed" — and a breathing exercise needs no network.
 */
export const UNAVAILABLE_REPLY = `I can't reach my language service right now, so I'm a bit \
limited — but I'm still here.

If you want something to do in the meantime: breathe in for four counts, hold for four, out for \
six. Repeat that four times. Longer out-breaths than in-breaths is the part that does the work.

Try me again in a moment.`;

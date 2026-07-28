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

WHEN ASKED FOR SOMETHING YOU CANNOT GIVE
Say plainly that it is outside what you can help with, say why in one sentence, and point toward a \
doctor or a mental-health professional. Do not apologise repeatedly or lecture. Then return to how \
the person is feeling.

TONE
Speak like a calm friend who happens to be a good listener. No clinical register, no bullet lists, \
no headings, no emoji.`;

/**
 * Turns the app's reading into an instruction the model can follow.
 *
 * Built here rather than on the client so the framing cannot be edited by anyone with the APK.
 * The framing is the whole point: handed a bare "heart rate 92", a model will reach for clinical
 * interpretation, which §18 forbids. Told instead that 92 is what the app measured and that heart
 * rate is what the *model* weighted most, it can be specific about the reading without making a
 * claim about the person's health.
 */
export function contextPrompt(context: StressContext): string {
  const lines: string[] = [
    "WHAT THE APP CURRENTLY MEASURES FOR THIS PERSON",
    `The app's stress model reads their current state as: ${context.label}.`,
    `Latest readings: heart rate ${context.heart_rate} bpm, ` +
      `${context.daily_steps} steps today, ${context.sleep_hours} hours of sleep.`,
  ];

  if (context.main_driver) {
    lines.push(
      `Of the things that change day to day, the model weighted this most heavily: ` +
        `${context.main_driver}.`,
    );
  }
  if (context.drivers?.length) {
    lines.push(
      `How each input compares to typical: ${context.drivers.join("; ")}.`,
      // The app has no personal baseline for this person -- it has never established what their
      // own resting heart rate is. "Typical" is the median of the training population, and
      // saying "your typical" claims a comparison that was never made.
      `"Typical" means the median of the population the model was trained on, NOT this person's ` +
        `own baseline. The app has no personal baseline for them. Never say "your typical" or ` +
        `"higher than usual for you" - say "higher than typical" or "above the usual range".`,
    );
  }

  // Stated because the honest answer to "why am I stressed" is sometimes "not because of anything
  // you did today", and an assistant that always blames today's readings would be reassuring and
  // wrong.
  if (context.profile_dominates) {
    lines.push(
      "CRITICAL: most of this reading comes from fixed profile details such as age and " +
        "occupation, NOT from today's measurements. If they ask why they are stressed, you MUST " +
        "tell them the model is leaning mainly on their background profile rather than anything " +
        "they did today, and that today's readings look ordinary. Do not deflect with a question " +
        "instead of saying this, and never imply today's readings are the reason.",
    );
  }

  if (context.extrapolating) {
    // Made mandatory and unmissable after the softer wording was ignored: asked for their stress
    // level on a reading well outside the trained range, the model reported it as fact. An
    // extrapolation presented confidently is worse than no reading, because the user has no way
    // to know it is guesswork.
    lines.push(
      "CRITICAL: these readings fall OUTSIDE the range the model was trained on, so it is " +
        "extrapolating and this estimate is unreliable. You MUST say so in any reply that " +
        "mentions the stress reading. Do not state the stress level as fact. Say the reading is " +
        "outside what the app can measure reliably and should not be trusted.",
    );
  }

  lines.push(
    "",
    "HOW TO USE THIS",
    // Spelled out because the general instruction to lead with feelings was strong enough to
    // override it: asked "why am I stressed", the model deflected into another open question
    // while holding the answer. A person who asks a direct question is owed a direct answer.
    "If they ask why they are stressed, what the app is seeing, or anything about their readings, " +
      "ANSWER IT DIRECTLY using the numbers above and name the main driver. Do not respond with " +
      "a question instead. Answer first, in one or two sentences, then you may ask how it lands.",
    "If they are not asking about the data, do not lead with it. Follow what they raised.",
    "These are wearable measurements and a model's estimate, not medical findings. Never say a " +
      "reading is high or low in a clinical sense, never suggest what it means for their health, " +
      "and never present the model's output as a fact about their body. It is what an app " +
      "calculated, and it can be wrong.",
  );

  return lines.join("\n");
}

/**
 * The caveat appended when the model is extrapolating and the reply did not say so.
 *
 * Fixed text, added in code rather than asked for in the prompt, for the same reason the crisis
 * reply is fixed: the instruction was ignored twice, including when phrased as "CRITICAL ... you
 * MUST". Told the readings were far outside the trained range, the model still answered "your
 * current state is stressed" as plain fact. A prompt shapes behaviour; it does not guarantee it,
 * and the guarantee is the point here — an extrapolation presented confidently is worse than no
 * reading at all, because the user has no way to know it is guesswork.
 */
export const EXTRAPOLATION_CAVEAT =
  "\n\n(One thing I should be straight about: your readings are outside the range this app's " +
  "model was built for, so that stress estimate is unreliable. Treat it as a rough guess rather " +
  "than a measurement.)";

/** Hedges that mean the model already admitted the limitation, so appending would be nagging. */
const HEDGE_MARKERS = [
  "outside",
  "unreliable",
  "rough estimate",
  "rough guess",
  "not reliable",
  "cannot measure",
  "can't measure",
  "not something to trust",
  "take it with",
];

/**
 * Adds the caveat to a reply that needs one, and leaves an already-honest reply alone.
 *
 * Only applied when the reply actually refers to the reading. A conversation can drift onto
 * something else entirely, and stapling a disclaimer about heart rate onto a reply about someone's
 * argument with their brother would be noise rather than honesty.
 */
export function withExtrapolationCaveat(reply: string, extrapolating: boolean): string {
  if (!extrapolating) return reply;

  const lower = reply.toLowerCase();
  if (HEDGE_MARKERS.some((marker) => lower.includes(marker))) return reply;

  const mentionsReading = ["stress", "heart rate", "bpm", "reading", "steps", "sleep"]
    .some((term) => lower.includes(term));
  if (!mentionsReading) return reply;

  return reply + EXTRAPOLATION_CAVEAT;
}

/** What the app sends about the person; every field is optional from the model's point of view. */
export interface StressContext {
  label: string;
  heart_rate: number;
  daily_steps: number;
  sleep_hours: number;
  main_driver?: string | null;
  drivers?: string[];
  profile_dominates?: boolean;
  extrapolating?: boolean;
}

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

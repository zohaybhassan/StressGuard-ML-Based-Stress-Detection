/**
 * The chatbot's backend, plan §18.
 *
 * This function exists for one reason above all others: the Hugging Face token must not be in the
 * Android app. Plan §4 says so and §18's exit criteria test for it. An APK is a zip file anyone
 * can unpack, so a token shipped inside one is a published token — here it is a Supabase secret
 * that never leaves the server.
 *
 * The function also owns the safety behaviour. Putting the system prompt and the crisis check on
 * the client would mean they could be edited or bypassed by anyone with the APK, and the app's
 * only guarantee about what the assistant will say would be a guarantee about its own good
 * intentions.
 *
 * Deploy:
 *   supabase secrets set HUGGINGFACE_TOKEN=hf_xxx
 *   supabase functions deploy chat
 */

import { CRISIS_REPLY, isCrisis, SYSTEM_PROMPT, UNAVAILABLE_REPLY } from "./safety.ts";

/**
 * OpenAI-compatible chat completions, routed by Hugging Face.
 *
 * The router rather than a per-model endpoint, so changing MODEL below is a one-line change and
 * does not need a different URL shape.
 */
const HF_ENDPOINT = "https://router.huggingface.co/v1/chat/completions";

/**
 * A general instruct model constrained by [SYSTEM_PROMPT], rather than a community fine-tune
 * trained on counselling transcripts. The behaviour of this one is documented and the constraints
 * on it are visible in this repository, which is what makes the choice defensible.
 */
const MODEL = "meta-llama/Llama-3.1-8B-Instruct";

/** Replies are short by design; this is a ceiling, not a target. */
const MAX_TOKENS = 300;

/**
 * How much conversation is sent back to the model.
 *
 * Bounded because the whole transcript would grow without limit and every turn would cost more
 * than the last. Six exchanges is enough for the model to remember what is being discussed.
 */
const HISTORY_TURNS = 12;

/** Bounded so a single request cannot be used to push a large payload through the token. */
const MAX_MESSAGE_CHARS = 2000;

interface Turn {
  role: "user" | "assistant";
  content: string;
}

interface ChatRequest {
  message: string;
  history?: Turn[];
}

interface ChatResponse {
  reply: string;
  /** True when the reply came from the safety layer rather than the model. */
  isFallback: boolean;
  /** True specifically when the crisis check fired, so the session can record it. */
  isCrisis: boolean;
}

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

function json(body: ChatResponse | { error: string }, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      ...CORS_HEADERS,
      // The charset is stated rather than left to the client to guess. Without it a client that
      // defaults to Latin-1 renders every non-ASCII character as mojibake, and the reply that
      // must survive that intact is the crisis one -- a garbled helpline is a helpline nobody
      // calls.
      "Content-Type": "application/json; charset=utf-8",
    },
  });
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: CORS_HEADERS });
  }

  // Supabase verifies the JWT before this function runs, but an unauthenticated call would arrive
  // with no header at all, and answering it would make the token available to anyone who knows
  // the URL.
  if (!req.headers.get("Authorization")) {
    return json({ error: "unauthorized" }, 401);
  }

  let body: ChatRequest;
  try {
    body = await req.json();
  } catch {
    return json({ error: "malformed request" }, 400);
  }

  const message = (body.message ?? "").trim();
  if (!message) {
    return json({ error: "empty message" }, 400);
  }
  if (message.length > MAX_MESSAGE_CHARS) {
    return json({ error: "message too long" }, 413);
  }

  // Before anything else, and before the model sees a single character. A person in crisis gets a
  // reviewed answer naming a real service, not a generated one.
  if (isCrisis(message)) {
    return json({ reply: CRISIS_REPLY, isFallback: true, isCrisis: true });
  }

  const token = Deno.env.get("HUGGINGFACE_TOKEN");
  if (!token) {
    // A deployment problem, not a user problem. Logged for the developer, and the user still gets
    // something useful rather than an error code.
    console.error("HUGGINGFACE_TOKEN is not set; returning the offline fallback");
    return json({ reply: UNAVAILABLE_REPLY, isFallback: true, isCrisis: false });
  }

  const history = (body.history ?? [])
    .filter((turn) => turn.role === "user" || turn.role === "assistant")
    .slice(-HISTORY_TURNS);

  try {
    const upstream = await fetch(HF_ENDPOINT, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: MODEL,
        max_tokens: MAX_TOKENS,
        // Warm enough not to sound like a form letter, low enough to stay inside the boundaries
        // the system prompt sets.
        temperature: 0.7,
        messages: [
          { role: "system", content: SYSTEM_PROMPT },
          ...history,
          { role: "user", content: message },
        ],
      }),
      // Hugging Face can cold-start a model. A stressed person watching a blank screen is worse
      // than a prompt fallback, so the wait is bounded.
      signal: AbortSignal.timeout(25_000),
    });

    if (!upstream.ok) {
      console.error(`Hugging Face returned ${upstream.status}: ${await upstream.text()}`);
      return json({ reply: UNAVAILABLE_REPLY, isFallback: true, isCrisis: false });
    }

    const payload = await upstream.json();
    const reply: string | undefined = payload?.choices?.[0]?.message?.content?.trim();

    if (!reply) {
      console.error("Hugging Face returned no message content");
      return json({ reply: UNAVAILABLE_REPLY, isFallback: true, isCrisis: false });
    }

    return json({ reply, isFallback: false, isCrisis: false });
  } catch (error) {
    // Timeout, DNS, TLS, upstream outage. All of them mean the same thing to the person waiting.
    console.error("chat upstream failed", error);
    return json({ reply: UNAVAILABLE_REPLY, isFallback: true, isCrisis: false });
  }
});

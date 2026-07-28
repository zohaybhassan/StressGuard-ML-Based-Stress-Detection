/**
 * Tests for the crisis check, plan §18.
 *
 * This is the one piece of the chatbot where being wrong is not recoverable, so it is the one
 * piece tested directly rather than through the assembled function. Run with:
 *
 *   deno test supabase/functions/chat/safety_test.ts
 */

import { assert, assertEquals, assertFalse } from "jsr:@std/assert";
import { CRISIS_REPLY, isCrisis, normalise, SYSTEM_PROMPT } from "./safety.ts";

Deno.test("plain statements of intent are caught", () => {
  assert(isCrisis("i want to die"));
  assert(isCrisis("I want to kill myself"));
  assert(isCrisis("thinking about ending my life"));
  assert(isCrisis("everyone would be better off dead"));
  assert(isCrisis("I have been having suicidal thoughts"));
});

Deno.test("self-harm is caught as well as suicide", () => {
  assert(isCrisis("i want to hurt myself"));
  assert(isCrisis("I have been cutting myself again"));
  assert(isCrisis("thinking about self harm"));
  assert(isCrisis("I took an overdose"));
});

Deno.test("shouting and punctuation do not defeat the check", () => {
  // The realistic way a check like this fails is not clever evasion, it is ordinary typing.
  assert(isCrisis("I WANT TO DIE"));
  assert(isCrisis("i want to... die"));
  assert(isCrisis("I want to die!!!"));
  assert(isCrisis("i   want   to   die"));
  assert(isCrisis("Honestly? I want to die."));
});

Deno.test("ordinary stressed speech is not treated as a crisis", () => {
  // The whole point of the app is talking to people who say things like this. Firing a helpline
  // at them would be both useless and insulting.
  assertFalse(isCrisis("this deadline is killing me"));
  assertFalse(isCrisis("my boss is murdering me with these hours"));
  assertFalse(isCrisis("I'm dead tired"));
  assertFalse(isCrisis("I'm dying to get some sleep"));
  assertFalse(isCrisis("that presentation nearly killed me"));
  assertFalse(isCrisis("I could kill for a coffee right now"));
});

Deno.test("normal supportive conversation passes through", () => {
  assertFalse(isCrisis("I feel overwhelmed by work"));
  assertFalse(isCrisis("I can't sleep and my heart is racing"));
  assertFalse(isCrisis("I had a panic attack this morning"));
  assertFalse(isCrisis("everything feels like too much"));
});

Deno.test("normalise strips what stands between typing and matching", () => {
  assertEquals(normalise("  I WANT   to,,, DIE!!  "), "i want to die");
  assertEquals(normalise("self-harm"), "self harm");
});

Deno.test("the crisis reply names a real service and does not counsel", () => {
  // Fixed wording is the reason this path bypasses the model, so the wording is asserted.
  assert(CRISIS_REPLY.includes("1122"), "must name an emergency number");
  assert(CRISIS_REPLY.includes("0311 7786264"), "must name a crisis line");
  assert(
    CRISIS_REPLY.includes("I am an app, not a person"),
    "must not let the user believe they reached a human",
  );
});

Deno.test("the system prompt states the boundaries §18 requires", () => {
  const prompt = SYSTEM_PROMPT.toLowerCase();
  assert(prompt.includes("never diagnose"));
  assert(prompt.includes("medication"));
  assert(prompt.includes("therapist"));
});

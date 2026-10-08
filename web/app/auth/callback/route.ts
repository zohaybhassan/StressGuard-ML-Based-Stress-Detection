import type { EmailOtpType } from "@supabase/supabase-js";
import { NextResponse, type NextRequest } from "next/server";

import {
  callbackErrorKind,
  getSiteUrl,
  safeNextPath,
} from "@/lib/auth/redirects";
import { hasExternalAuthProvider } from "@/lib/auth/providers";
import { PASSWORD_RECOVERY_COOKIE } from "@/lib/auth/session-cookies";
import { createSupabaseServerClient } from "@/lib/supabase/server";

const emailOtpTypes = new Set<EmailOtpType>([
  "email",
  "email_change",
  "invite",
  "magiclink",
  "recovery",
  "signup",
]);

function appRedirect(path: string) {
  return NextResponse.redirect(new URL(path, getSiteUrl()));
}

function authRedirect(query: string) {
  return appRedirect(`/auth?${query}`);
}

export async function GET(request: NextRequest) {
  if (request.nextUrl.searchParams.has("error")) {
    const error = callbackErrorKind(
      request.nextUrl.searchParams.get("error_code"),
      request.nextUrl.searchParams.get("next"),
    );
    return authRedirect(`error=${error}`);
  }

  const supabase = await createSupabaseServerClient();
  if (!supabase) return authRedirect("reason=configuration");

  const code = request.nextUrl.searchParams.get("code");
  const tokenHash = request.nextUrl.searchParams.get("token_hash");
  const rawType = request.nextUrl.searchParams.get("type");
  const type = rawType && emailOtpTypes.has(rawType as EmailOtpType)
    ? (rawType as EmailOtpType)
    : null;

  let error = null;
  if (code) {
    ({ error } = await supabase.auth.exchangeCodeForSession(code));
  } else if (tokenHash && type) {
    ({ error } = await supabase.auth.verifyOtp({ token_hash: tokenHash, type }));
  } else {
    return authRedirect("error=invalid-link");
  }

  if (error) return authRedirect("error=expired-link");

  const next = safeNextPath(request.nextUrl.searchParams.get("next"));
  const isRecovery = type === "recovery" || next.startsWith("/auth?mode=reset");

  if (isRecovery) {
    const response = appRedirect("/auth?mode=reset");
    response.cookies.set(PASSWORD_RECOVERY_COOKIE, "active", {
      httpOnly: true,
      maxAge: 15 * 60,
      path: "/",
      sameSite: "lax",
      secure: process.env.NODE_ENV === "production",
    });
    return response;
  }

  const {
    data: { user },
  } = await supabase.auth.getUser();

  if (user) {
    const { data: profile } = await supabase
      .from("profiles")
      .select("password_set")
      .eq("id", user.id)
      .maybeSingle();

    if (profile?.password_set === false && !hasExternalAuthProvider(user)) {
      return appRedirect("/auth?mode=set-password");
    }
  }

  return appRedirect(next);
}

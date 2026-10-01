import type { EmailOtpType } from "@supabase/supabase-js";
import { NextResponse, type NextRequest } from "next/server";

import { safeNextPath } from "@/lib/auth/redirects";
import { createSupabaseServerClient } from "@/lib/supabase/server";

const emailOtpTypes = new Set<EmailOtpType>([
  "email",
  "email_change",
  "invite",
  "magiclink",
  "recovery",
  "signup",
]);

function authRedirect(request: NextRequest, query: string) {
  return NextResponse.redirect(new URL(`/auth?${query}`, request.url));
}

export async function GET(request: NextRequest) {
  if (request.nextUrl.searchParams.has("error")) {
    return authRedirect(request, "error=oauth");
  }

  const supabase = await createSupabaseServerClient();
  if (!supabase) return authRedirect(request, "reason=configuration");

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
    return authRedirect(request, "error=invalid-link");
  }

  if (error) return authRedirect(request, "error=expired-link");

  const next = safeNextPath(request.nextUrl.searchParams.get("next"));
  const isRecovery = type === "recovery" || next.startsWith("/auth?mode=reset");

  if (isRecovery) {
    const response = NextResponse.redirect(new URL("/auth?mode=reset", request.url));
    response.cookies.set("sg-password-recovery", "active", {
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

    if (profile?.password_set === false) {
      return NextResponse.redirect(new URL("/auth?mode=set-password", request.url));
    }
  }

  return NextResponse.redirect(new URL(next, request.url));
}

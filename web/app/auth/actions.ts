"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";

import { friendlyAuthError } from "@/lib/auth/errors";
import { callbackUrl } from "@/lib/auth/redirects";
import {
  forgotPasswordSchema,
  formValue,
  newPasswordSchema,
  registerSchema,
  signInSchema,
} from "@/lib/auth/validation";
import { createSupabaseServerClient } from "@/lib/supabase/server";

import type { AuthActionState } from "./auth-state";

function invalid(fieldErrors: AuthActionState["fieldErrors"]): AuthActionState {
  return { status: "error", message: "Check the highlighted fields.", fieldErrors };
}

function notConfigured(): AuthActionState {
  return {
    status: "error",
    message: "Sign in is not configured yet. Add the public Supabase environment values.",
  };
}

export async function signInAction(
  _previous: AuthActionState,
  formData: FormData,
): Promise<AuthActionState> {
  const parsed = signInSchema.safeParse({
    email: formValue(formData, "email"),
    password: formValue(formData, "password"),
  });
  if (!parsed.success) return invalid(parsed.error.flatten().fieldErrors);

  const supabase = await createSupabaseServerClient();
  if (!supabase) return notConfigured();

  try {
    const { error } = await supabase.auth.signInWithPassword(parsed.data);
    if (error) return { status: "error", message: friendlyAuthError(error) };
  } catch (error) {
    return { status: "error", message: friendlyAuthError(error as Error) };
  }

  redirect("/dashboard");
}

export async function registerAction(
  _previous: AuthActionState,
  formData: FormData,
): Promise<AuthActionState> {
  const parsed = registerSchema.safeParse({
    email: formValue(formData, "email"),
    password: formValue(formData, "password"),
    confirmPassword: formValue(formData, "confirmPassword"),
  });
  if (!parsed.success) return invalid(parsed.error.flatten().fieldErrors);

  const supabase = await createSupabaseServerClient();
  if (!supabase) return notConfigured();

  try {
    const { data, error } = await supabase.auth.signUp({
      email: parsed.data.email,
      password: parsed.data.password,
      options: {
        emailRedirectTo: callbackUrl("/auth?status=verified"),
      },
    });

    if (error) return { status: "error", message: friendlyAuthError(error) };
    if (!data.session) {
      return {
        status: "confirmation",
        message:
          "Account created. Check your inbox for a confirmation link, then return to sign in.",
      };
    }
  } catch (error) {
    return { status: "error", message: friendlyAuthError(error as Error) };
  }

  redirect("/dashboard");
}

export async function startGoogleOAuthAction() {
  const supabase = await createSupabaseServerClient();
  if (!supabase) redirect("/auth?reason=configuration");

  let providerUrl: string | null = null;
  try {
    const { data, error } = await supabase.auth.signInWithOAuth({
      provider: "google",
      options: {
        redirectTo: callbackUrl("/dashboard"),
      },
    });
    if (!error) providerUrl = data.url;
  } catch {
    providerUrl = null;
  }

  if (!providerUrl) redirect("/auth?error=oauth");
  redirect(providerUrl);
}

export async function forgotPasswordAction(
  _previous: AuthActionState,
  formData: FormData,
): Promise<AuthActionState> {
  const parsed = forgotPasswordSchema.safeParse({ email: formValue(formData, "email") });
  if (!parsed.success) return invalid(parsed.error.flatten().fieldErrors);

  const supabase = await createSupabaseServerClient();
  if (!supabase) return notConfigured();

  try {
    // Always return the same result after a valid submission. This prevents account discovery.
    await supabase.auth.resetPasswordForEmail(parsed.data.email, {
      redirectTo: callbackUrl("/auth?mode=reset"),
    });
  } catch {
    // Deliberately concealed for the same account-enumeration reason.
  }

  return {
    status: "recovery-sent",
    message:
      "If an account uses that email, a password reset link is on its way. Check your spam folder too.",
  };
}

async function savePassword(
  formData: FormData,
  successPath: string,
  requireRecoveryCookie = false,
): Promise<AuthActionState> {
  const parsed = newPasswordSchema.safeParse({
    password: formValue(formData, "password"),
    confirmPassword: formValue(formData, "confirmPassword"),
  });
  if (!parsed.success) return invalid(parsed.error.flatten().fieldErrors);

  const supabase = await createSupabaseServerClient();
  if (!supabase) return notConfigured();

  const cookieStore = await cookies();
  if (
    requireRecoveryCookie &&
    cookieStore.get("sg-password-recovery")?.value !== "active"
  ) {
    return {
      status: "error",
      message: "This secure link is no longer active. Request a new password reset email.",
    };
  }

  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user) {
    return {
      status: "error",
      message: "This secure link is no longer active. Request a new password reset email.",
    };
  }

  try {
    const { error: passwordError } = await supabase.auth.updateUser({
      password: parsed.data.password,
    });
    if (passwordError) {
      return { status: "error", message: friendlyAuthError(passwordError) };
    }

    // The Android and web clients share this flag. It is only changed after Supabase accepts
    // the password, matching ProfileRepository.markPasswordSet() on Android.
    const { error: profileError } = await supabase
      .from("profiles")
      .update({ password_set: true })
      .eq("id", user.id);
    if (profileError) {
      return {
        status: "error",
        message: "Your password was saved, but account setup could not finish. Please try again.",
      };
    }
  } catch (error) {
    return { status: "error", message: friendlyAuthError(error as Error) };
  }

  if (requireRecoveryCookie) {
    cookieStore.set("sg-password-recovery", "", {
      httpOnly: true,
      maxAge: 0,
      path: "/",
      sameSite: "lax",
      secure: process.env.NODE_ENV === "production",
    });
  }

  redirect(successPath);
}

export async function resetPasswordAction(
  _previous: AuthActionState,
  formData: FormData,
) {
  return savePassword(formData, "/dashboard", true);
}

export async function setGooglePasswordAction(
  _previous: AuthActionState,
  formData: FormData,
) {
  return savePassword(formData, "/dashboard");
}

export async function signOutAction() {
  const supabase = await createSupabaseServerClient();
  if (supabase) await supabase.auth.signOut();
  redirect("/auth?status=signed-out");
}

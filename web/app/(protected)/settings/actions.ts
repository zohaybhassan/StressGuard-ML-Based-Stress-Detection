"use server";

import { revalidatePath } from "next/cache";
import { z } from "zod";

import { friendlyAuthError } from "@/lib/auth/errors";
import { formValue, newPasswordSchema } from "@/lib/auth/validation";
import { createSupabaseServerClient } from "@/lib/supabase/server";

export type SettingsActionState = {
  status: "idle" | "success" | "error";
  message: string;
};

const displayNameSchema = z.string().trim().min(1, "Enter your name.").max(80, "Use 80 characters or fewer.");

export async function updateDisplayNameAction(
  _previous: SettingsActionState,
  formData: FormData,
): Promise<SettingsActionState> {
  const parsed = displayNameSchema.safeParse(formValue(formData, "displayName"));
  if (!parsed.success) return { status: "error", message: parsed.error.issues[0].message };

  const supabase = await createSupabaseServerClient();
  if (!supabase) return { status: "error", message: "Account service is unavailable." };
  const { data: { user }, error: authError } = await supabase.auth.getUser();
  if (authError || !user) return { status: "error", message: "Your session expired. Sign in again." };

  const { data, error } = await supabase
    .from("profiles")
    .update({ display_name: parsed.data })
    .eq("id", user.id)
    .select("id")
    .maybeSingle();
  if (error || !data) return { status: "error", message: "Your name could not be saved. Try again." };

  revalidatePath("/dashboard");
  revalidatePath("/settings");
  revalidatePath("/(protected)", "layout");
  return { status: "success", message: "Your name has been updated." };
}

export async function updateAccountPasswordAction(
  _previous: SettingsActionState,
  formData: FormData,
): Promise<SettingsActionState> {
  const parsed = newPasswordSchema.safeParse({
    password: formValue(formData, "password"),
    confirmPassword: formValue(formData, "confirmPassword"),
  });
  if (!parsed.success) return { status: "error", message: parsed.error.issues[0].message };

  const supabase = await createSupabaseServerClient();
  if (!supabase) return { status: "error", message: "Account service is unavailable." };
  const { data: { user }, error: authError } = await supabase.auth.getUser();
  if (authError || !user) return { status: "error", message: "Your session expired. Sign in again." };

  const { data: profile, error: profileReadError } = await supabase
    .from("profiles")
    .select("password_set")
    .eq("id", user.id)
    .maybeSingle();
  if (profileReadError || !profile) {
    return { status: "error", message: "Your account profile could not be loaded. Try again." };
  }

  const currentPassword = formValue(formData, "currentPassword");
  if (profile.password_set && !currentPassword) {
    return { status: "error", message: "Enter your current password to change it." };
  }

  const { error: passwordError } = await supabase.auth.updateUser({
    password: parsed.data.password,
    ...(profile.password_set ? { current_password: currentPassword } : {}),
  });
  if (passwordError) return { status: "error", message: friendlyAuthError(passwordError) };

  if (!profile.password_set) {
    const { error: flagError } = await supabase
      .from("profiles")
      .update({ password_set: true })
      .eq("id", user.id);
    if (flagError) {
      return { status: "error", message: "Password saved, but the app account flag could not update. Please contact support." };
    }
  }

  revalidatePath("/settings");
  return { status: "success", message: "Your password has been updated." };
}

export const PASSWORD_RECOVERY_COOKIE = "sg-password-recovery";

export function isSupabaseAuthCookie(name: string) {
  return /^sb-[a-z0-9]+-auth-token(?:$|[.-])/i.test(name);
}

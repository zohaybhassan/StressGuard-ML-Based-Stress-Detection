import type { AuthFieldErrors } from "@/lib/auth/validation";

export type AuthActionState = {
  status: "idle" | "error" | "confirmation" | "recovery-sent";
  message?: string;
  fieldErrors?: AuthFieldErrors;
};

export const initialAuthState: AuthActionState = { status: "idle" };

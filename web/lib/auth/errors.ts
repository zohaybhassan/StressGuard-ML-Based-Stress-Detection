type AuthErrorLike = {
  code?: string;
  message?: string;
  status?: number;
};

const MESSAGE_BY_CODE: Record<string, string> = {
  email_not_confirmed: "Confirm your email before signing in.",
  email_address_invalid: "Enter a valid email address.",
  invalid_credentials: "Email or password is incorrect.",
  over_email_send_rate_limit: "Please wait a moment before requesting another email.",
  over_request_rate_limit: "Too many attempts. Please wait a moment and try again.",
  same_password: "Choose a password you have not used for this account.",
  user_banned: "This account is unavailable. Contact support if you need help.",
  weak_password: "Choose a stronger password and try again.",
};

export function friendlyAuthError(error: AuthErrorLike | null | undefined) {
  if (!error) return "Something went wrong. Please try again.";
  if (error.code && MESSAGE_BY_CODE[error.code]) return MESSAGE_BY_CODE[error.code];

  const message = error.message?.toLowerCase() ?? "";
  if (message.includes("invalid login credentials")) return MESSAGE_BY_CODE.invalid_credentials;
  if (message.includes("email not confirmed")) return MESSAGE_BY_CODE.email_not_confirmed;
  if (message.includes("already registered") || message.includes("already exists")) {
    return "An account may already use this email. Try signing in or resetting your password.";
  }
  if (message.includes("password") && message.includes("weak")) return MESSAGE_BY_CODE.weak_password;
  if (error.status === 429 || message.includes("rate limit")) {
    return "Too many attempts. Please wait a moment and try again.";
  }
  if (message.includes("fetch") || message.includes("network")) {
    return "We could not reach StressGuard. Check your connection and try again.";
  }

  return "We could not complete that request. Please try again.";
}

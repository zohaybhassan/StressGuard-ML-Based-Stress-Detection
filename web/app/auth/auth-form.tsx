"use client";

import {
  ArrowLeft,
  ArrowRight,
  CheckCircle,
  EnvelopeSimple,
  Eye,
  EyeSlash,
  GoogleLogo,
  LockKey,
  WarningCircle,
} from "@phosphor-icons/react";
import Link from "next/link";
import { useActionState, useId, useState } from "react";
import { useFormStatus } from "react-dom";

import type { AuthMode } from "@/lib/auth/redirects";

import {
  forgotPasswordAction,
  registerAction,
  resetPasswordAction,
  setGooglePasswordAction,
  signInAction,
  startGoogleOAuthAction,
} from "./actions";
import { initialAuthState, type AuthActionState } from "./auth-state";
import styles from "./auth-reference.module.css";

type AuthFormProps = {
  configured: boolean;
  mode: AuthMode;
  notice?: { message: string; tone: "error" | "info" | "success" };
  sessionAvailable: boolean;
  userEmail?: string;
};

const copy: Record<AuthMode, { title: string; description: string; submit: string; pending: string }> = {
  "sign-in": {
    title: "Welcome back",
    description: "Sign in to continue to your StressGuard dashboard.",
    submit: "Sign in",
    pending: "Signing in...",
  },
  register: {
    title: "Create your account",
    description: "Use Google for the quickest setup, or register with your email.",
    submit: "Create account",
    pending: "Creating account...",
  },
  forgot: {
    title: "Reset your password",
    description: "Enter your email and we will send a secure reset link if an account matches.",
    submit: "Send reset link",
    pending: "Sending link...",
  },
  reset: {
    title: "Choose a new password",
    description: "Use at least 6 characters. Your new password will work on Android and web.",
    submit: "Save new password",
    pending: "Saving password...",
  },
  "set-password": {
    title: "Finish account setup",
    description: "Add a password so you can use either Google or email next time.",
    submit: "Save password",
    pending: "Saving password...",
  },
};

function PendingButton({ idle, pending }: { idle: string; pending: string }) {
  const { pending: isPending } = useFormStatus();
  return (
    <button className={styles.primaryButton} type="submit" disabled={isPending}>
      <span>{isPending ? pending : idle}</span>
      <ArrowRight size={18} weight="bold" aria-hidden />
    </button>
  );
}

function FieldError({ errors, id }: { errors?: string[]; id: string }) {
  if (!errors?.length) return null;
  return (
    <p className={styles.fieldError} id={id}>
      {errors[0]}
    </p>
  );
}

function PasswordField({
  error,
  label,
  name,
  autoComplete,
}: {
  error?: string[];
  label: string;
  name: "password" | "confirmPassword";
  autoComplete: string;
}) {
  const [visible, setVisible] = useState(false);
  const inputId = useId();
  const errorId = `${inputId}-error`;

  return (
    <div className={styles.fieldGroup}>
      <label htmlFor={inputId}>{label}</label>
      <div className={styles.passwordWrap}>
        <LockKey className={styles.fieldIcon} size={19} aria-hidden />
        <input
          aria-describedby={error?.length ? errorId : undefined}
          aria-invalid={Boolean(error?.length)}
          autoComplete={autoComplete}
          id={inputId}
          minLength={6}
          name={name}
          required
          type={visible ? "text" : "password"}
        />
        <button
          aria-label={visible ? `Hide ${label.toLowerCase()}` : `Show ${label.toLowerCase()}`}
          className={styles.revealButton}
          onClick={() => setVisible((current) => !current)}
          type="button"
        >
          {visible ? <EyeSlash size={19} aria-hidden /> : <Eye size={19} aria-hidden />}
        </button>
      </div>
      <FieldError errors={error} id={errorId} />
    </div>
  );
}

function StatusMessage({ state }: { state: AuthActionState }) {
  if (!state.message) return null;
  const positive = state.status === "confirmation" || state.status === "recovery-sent";
  return (
    <div
      className={positive ? styles.successMessage : styles.errorMessage}
      role={positive ? "status" : "alert"}
    >
      {positive ? (
        <CheckCircle size={20} weight="fill" aria-hidden />
      ) : (
        <WarningCircle size={20} weight="fill" aria-hidden />
      )}
      <span>{state.message}</span>
    </div>
  );
}

export function AuthForm({ configured, mode, notice, sessionAvailable, userEmail }: AuthFormProps) {
  const action =
    mode === "register"
      ? registerAction
      : mode === "forgot"
        ? forgotPasswordAction
        : mode === "reset"
          ? resetPasswordAction
          : mode === "set-password"
            ? setGooglePasswordAction
            : signInAction;
  const [state, formAction] = useActionState(action, initialAuthState);
  const details = copy[mode];
  const emailErrorId = "auth-email-error";
  const unavailable = (mode === "reset" || mode === "set-password") && !sessionAvailable;

  return (
    <div className={styles.formCard}>
      <div className={styles.formHeader}>
        <p className={styles.eyebrow}>
          {mode === "set-password" ? "One final step" : "StressGuard account"}
        </p>
        <h1>{details.title}</h1>
        <p>{details.description}</p>
        {mode === "set-password" && userEmail ? (
          <span className={styles.accountEmail}>{userEmail}</span>
        ) : null}
      </div>

      {notice ? (
        <div
          className={notice.tone === "error" ? styles.errorMessage : styles.infoMessage}
          role={notice.tone === "error" ? "alert" : "status"}
        >
          {notice.tone === "error" ? (
            <WarningCircle size={20} weight="fill" aria-hidden />
          ) : (
            <CheckCircle size={20} weight="fill" aria-hidden />
          )}
          <span>{notice.message}</span>
        </div>
      ) : null}

      {unavailable ? (
        <div className={styles.expiredPanel}>
          <p>This secure session is no longer active.</p>
          <Link className={styles.primaryButton} href="/auth?mode=forgot">
            Request a new reset link
            <ArrowRight size={18} aria-hidden />
          </Link>
        </div>
      ) : (
        <>
          <form action={formAction} className={styles.form} noValidate>
            {(mode === "sign-in" || mode === "register" || mode === "forgot") && (
              <div className={styles.fieldGroup}>
                <label htmlFor="auth-email">Email address</label>
                <div className={styles.inputWrap}>
                  <EnvelopeSimple className={styles.fieldIcon} size={19} aria-hidden />
                  <input
                    aria-describedby={state.fieldErrors?.email ? emailErrorId : undefined}
                    aria-invalid={Boolean(state.fieldErrors?.email)}
                    autoComplete="email"
                    id="auth-email"
                    inputMode="email"
                    name="email"
                    placeholder="you@example.com"
                    required
                    type="email"
                  />
                </div>
                <FieldError errors={state.fieldErrors?.email} id={emailErrorId} />
              </div>
            )}

            {mode !== "forgot" && (
              <PasswordField
                autoComplete={mode === "sign-in" ? "current-password" : "new-password"}
                error={state.fieldErrors?.password}
                label={mode === "sign-in" ? "Password" : "New password"}
                name="password"
              />
            )}

            {(mode === "register" || mode === "reset" || mode === "set-password") && (
              <PasswordField
                autoComplete="new-password"
                error={state.fieldErrors?.confirmPassword}
                label="Confirm password"
                name="confirmPassword"
              />
            )}

            {mode === "sign-in" && (
              <div className={styles.formOptions}>
                <label className={styles.rememberOption}>
                  <input name="remember" type="checkbox" />
                  <span>Remember me</span>
                </label>
                <Link className={styles.forgotLink} href="/auth?mode=forgot">
                  Forgot password?
                </Link>
              </div>
            )}

            <StatusMessage state={state} />
            <PendingButton idle={details.submit} pending={details.pending} />
          </form>

          {(mode === "sign-in" || mode === "register") && (
            <>
              <div className={styles.divider}>
                <span>or continue with</span>
              </div>
              <form action={startGoogleOAuthAction}>
                <button className={styles.googleButton} disabled={!configured} type="submit">
                  <GoogleLogo size={20} weight="bold" aria-hidden />
                  Continue with Google
                </button>
              </form>
            </>
          )}
        </>
      )}

      {!unavailable && mode === "sign-in" && (
        <p className={styles.switchMode}>
          New to StressGuard? <Link href="/auth?mode=register">Create an account</Link>
        </p>
      )}
      {!unavailable && mode === "register" && (
        <p className={styles.switchMode}>
          Already have an account? <Link href="/auth">Sign in</Link>
        </p>
      )}
      {(mode === "forgot" || (mode === "reset" && !sessionAvailable)) && (
        <Link className={styles.backLink} href="/auth">
          <ArrowLeft size={17} aria-hidden /> Back to sign in
        </Link>
      )}
    </div>
  );
}

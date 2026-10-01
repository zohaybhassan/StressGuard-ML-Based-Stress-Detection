import {
  ArrowLeft,
  ArrowRight,
  CheckCircle,
  CloudCheck,
  DeviceMobile,
  ShieldCheck,
} from "@phosphor-icons/react/dist/ssr";
import { cookies } from "next/headers";
import Link from "next/link";
import { redirect } from "next/navigation";

import { BrandLockup } from "@/components/brand/brand-lockup";
import { getPasswordGate } from "@/lib/auth/password-gate";
import { authMode } from "@/lib/auth/redirects";

import { AuthForm } from "./auth-form";
import styles from "./auth.module.css";

type AuthPageProps = {
  searchParams: Promise<{
    error?: string | string[];
    mode?: string | string[];
    reason?: string | string[];
    status?: string | string[];
  }>;
};

export const metadata = { title: "Sign in" };
export const dynamic = "force-dynamic";

function first(value: string | string[] | undefined) {
  return Array.isArray(value) ? value[0] : value;
}

function noticeFor(reason?: string, error?: string, status?: string) {
  if (reason === "configuration") {
    return {
      message: "Add the public Supabase URL and publishable key to enable secure sign in.",
      tone: "error" as const,
    };
  }
  if (reason === "session") {
    return { message: "Sign in to continue to your StressGuard portal.", tone: "info" as const };
  }
  if (error === "expired-link") {
    return {
      message: "This secure link has expired or was already used. Request a new one and try again.",
      tone: "error" as const,
    };
  }
  if (error === "invalid-link") {
    return { message: "This authentication link is incomplete.", tone: "error" as const };
  }
  if (error === "oauth") {
    return {
      message: "Google sign in could not be completed. You can try again or use email.",
      tone: "error" as const,
    };
  }
  if (status === "signed-out") {
    return { message: "You have been signed out securely.", tone: "success" as const };
  }
  if (status === "verified") {
    return { message: "Your email is confirmed. You can now sign in.", tone: "success" as const };
  }
  return undefined;
}

export default async function AuthPage({ searchParams }: AuthPageProps) {
  const params = await searchParams;
  const reason = first(params.reason);
  const error = first(params.error);
  const status = first(params.status);
  let mode = authMode(first(params.mode));
  const gate = await getPasswordGate();
  const cookieStore = await cookies();
  const recoveryActive = cookieStore.get("sg-password-recovery")?.value === "active";

  if (gate.user && !gate.passwordSet) {
    mode = "set-password";
  } else if (gate.user && mode === "reset" && recoveryActive) {
    // A recovery exchange creates a short-lived authenticated session. Keep it on the reset form.
  } else if (gate.user && status === "verified") {
    // Show confirmation feedback once before the user enters the portal.
  } else if (gate.user) {
    redirect("/dashboard");
  }

  const missingSetupSession = !gate.user && mode === "set-password";
  if (missingSetupSession) mode = "sign-in";

  const notice = missingSetupSession
    ? {
        message: "Your Google setup session is no longer active. Sign in with Google to continue.",
        tone: "error" as const,
      }
    : !gate.configured
      ? noticeFor("configuration", error, status)
      : noticeFor(reason, error, status);

  return (
    <main className={styles.authPage}>
      <section className={styles.storyPanel} aria-labelledby="auth-story-title">
        <div className={styles.storyInner}>
          <BrandLockup />
          <div className={styles.storyCopy}>
            <p className={styles.storyEyebrow}>Your private wellness companion</p>
            <h2 id="auth-story-title">Your patterns, in one calm place.</h2>
            <p>
              Review stress, heart rate, sleep, and activity insights synchronized from the
              StressGuard Android and Wear OS apps.
            </p>
            <div className={styles.assuranceList}>
              <div className={styles.assuranceItem}>
                <span className={styles.assuranceIcon}>
                  <ShieldCheck size={18} weight="duotone" aria-hidden />
                </span>
                Your authenticated account controls access to your private health data.
              </div>
              <div className={styles.assuranceItem}>
                <span className={styles.assuranceIcon}>
                  <CloudCheck size={18} weight="duotone" aria-hidden />
                </span>
                Web insights reflect data synchronized by the mobile app.
              </div>
              <div className={styles.assuranceItem}>
                <span className={styles.assuranceIcon}>
                  <DeviceMobile size={18} weight="duotone" aria-hidden />
                </span>
                Wearable sensing and alerts continue on Android and Wear OS.
              </div>
            </div>
          </div>
          <div className={styles.storyFooter}>
            <span>StressGuard does not diagnose medical conditions.</span>
            <Link href="/">
              <ArrowLeft size={16} aria-hidden /> Website
            </Link>
          </div>
        </div>
      </section>

      <section className={styles.formPanel} aria-label="Account access">
        <div className={styles.formWrap}>
          <div className={styles.mobileHeader}>
            <BrandLockup />
            <Link className={styles.homeLink} href="/">
              <ArrowLeft size={16} aria-hidden /> Home
            </Link>
          </div>

          {gate.user && status === "verified" && gate.passwordSet ? (
            <div className={styles.completionCard} role="status">
              <span className={styles.completionIcon}>
                <CheckCircle size={28} weight="fill" aria-hidden />
              </span>
              <h1>Email confirmed</h1>
              <p>Your StressGuard account is ready. Continue to your private portal.</p>
              <Link className={styles.primaryButton} href="/dashboard">
                Continue to dashboard <ArrowRight size={18} aria-hidden />
              </Link>
            </div>
          ) : (
            <AuthForm
              configured={gate.configured}
              mode={mode}
              notice={notice}
              sessionAvailable={Boolean(gate.user) && (mode !== "reset" || recoveryActive)}
              userEmail={gate.user?.email}
            />
          )}
        </div>
      </section>
    </main>
  );
}

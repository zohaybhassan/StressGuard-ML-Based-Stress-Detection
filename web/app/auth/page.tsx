import {
  ArrowLeft,
  ArrowRight,
  ChartLineUp,
  CheckCircle,
  Heart,
  Leaf,
  MoonStars,
  Pulse,
} from "@phosphor-icons/react/dist/ssr";
import { cookies } from "next/headers";
import Image from "next/image";
import Link from "next/link";
import { redirect } from "next/navigation";

import { BrandLockup } from "@/components/brand/brand-lockup";
import { getPasswordGate } from "@/lib/auth/password-gate";
import { authMode } from "@/lib/auth/redirects";
import { PASSWORD_RECOVERY_COOKIE } from "@/lib/auth/session-cookies";

import { AuthForm } from "./auth-form";
import styles from "./auth-reference.module.css";

type AuthPageProps = {
  searchParams: Promise<{
    error?: string | string[];
    mode?: string | string[];
    reason?: string | string[];
    status?: string | string[];
  }>;
};

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
  const recoveryActive = cookieStore.get(PASSWORD_RECOVERY_COOKIE)?.value === "active";

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
          <div className={styles.storyContent}>
            <div className={styles.storyCopy}>
              <p className={styles.storyEyebrow}>A calmer mind. A healthier you.</p>
              <h2 id="auth-story-title">
                Understand your stress. <span>Build a healthier tomorrow.</span>
              </h2>
              <p>
                Track body signals, discover patterns, and get personalized guidance with
                StressGuard.
              </p>
              <div className={styles.assuranceList}>
                <div className={styles.assuranceItem}>
                  <span className={styles.assuranceIcon}>
                    <Heart size={21} weight="duotone" aria-hidden />
                  </span>
                  <span><strong>Track key signals</strong>Heart rate, sleep, activity, and stress inputs.</span>
                </div>
                <div className={styles.assuranceItem}>
                  <span className={`${styles.assuranceIcon} ${styles.progressIcon}`}>
                    <ChartLineUp size={21} weight="duotone" aria-hidden />
                  </span>
                  <span><strong>See your progress</strong>Understand patterns across your synced wellness data.</span>
                </div>
                <div className={styles.assuranceItem}>
                  <span className={`${styles.assuranceIcon} ${styles.supportIcon}`}>
                    <Leaf size={21} weight="duotone" aria-hidden />
                  </span>
                  <span><strong>Get personalized support</strong>Use insights and wellness tools that fit your routine.</span>
                </div>
              </div>
            </div>

            <div className={styles.watchStage} aria-label="StressGuard smartwatch with wellness insights">
              <span className={styles.backdropShape} aria-hidden />
              <span className={styles.watchPlinth} aria-hidden />
              <div className={styles.watchFigure} data-auth-watch>
                <Image
                  className={styles.watchImage}
                  src="/brand/hero-smartwatch-v5-three-quarter.png"
                  alt="Round StressGuard smartwatch"
                  width={1312}
                  height={1199}
                  priority
                  sizes="(max-width: 880px) 45vw, 32vw"
                />
                <div className={styles.watchFace} aria-hidden>
                  <Pulse size={18} weight="fill" />
                  <span>Stress level</span>
                  <strong>Low</strong>
                  <Leaf size={15} weight="fill" />
                </div>
              </div>

              <div className={`${styles.metricCard} ${styles.heartCard}`} data-auth-metric="heart">
                <span><Heart size={16} weight="fill" aria-hidden /> Heart rate</span>
                <strong>72 bpm</strong>
                <small>Within your usual range</small>
              </div>
              <div className={`${styles.metricCard} ${styles.sleepCard}`} data-auth-metric="sleep">
                <span><MoonStars size={16} weight="fill" aria-hidden /> Sleep</span>
                <strong>7h 30m</strong>
                <small>Restful night</small>
              </div>
              <div className={`${styles.metricCard} ${styles.trendCard}`} data-auth-metric="trend">
                <span><ChartLineUp size={16} weight="fill" aria-hidden /> Stress trend</span>
                <div className={styles.trendBars} aria-hidden>
                  <i /><i /><i /><i /><i /><i /><i />
                </div>
                <small>Steadier this week</small>
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
        <Link className={styles.helpLink} href="/auth?mode=forgot">Need help?</Link>
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

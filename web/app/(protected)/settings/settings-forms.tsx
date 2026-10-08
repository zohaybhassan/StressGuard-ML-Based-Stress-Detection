"use client";

import { ArrowUpRight, CheckCircle, Eye, EyeSlash, LockKey, ShieldCheck, UserCircle, WarningCircle } from "@phosphor-icons/react";
import Link from "next/link";
import { useActionState, useState } from "react";

import {
  updateAccountPasswordAction,
  updateDisplayNameAction,
  type SettingsActionState,
} from "./actions";
import styles from "./settings.module.css";

const initialSettingsState: SettingsActionState = { status: "idle", message: "" };

function Feedback({ state }: { state: SettingsActionState }) {
  if (!state.message) return null;
  return (
    <p className={state.status === "success" ? styles.success : styles.error} role={state.status === "error" ? "alert" : "status"}>
      {state.status === "success" ? <CheckCircle size={18} aria-hidden /> : <WarningCircle size={18} aria-hidden />}
      {state.message}
    </p>
  );
}

function PasswordField({ id, name, label, autoComplete }: {
  id: string;
  name: string;
  label: string;
  autoComplete: "current-password" | "new-password";
}) {
  const [visible, setVisible] = useState(false);
  return (
    <div className={styles.field}>
      <label htmlFor={id}>{label}</label>
      <div className={styles.passwordField}>
        <input
          id={id}
          name={name}
          type={visible ? "text" : "password"}
          autoComplete={autoComplete}
          minLength={autoComplete === "new-password" ? 6 : undefined}
          required
        />
        <button
          type="button"
          aria-label={`${visible ? "Hide" : "Show"} ${label.toLowerCase()}`}
          aria-pressed={visible}
          onClick={() => setVisible((previous) => !previous)}
        >
          {visible ? <EyeSlash size={19} aria-hidden /> : <Eye size={19} aria-hidden />}
        </button>
      </div>
    </div>
  );
}

export function SettingsForms({ displayName, email, hasPassword, usesGoogle = false }: {
  displayName: string;
  email: string;
  hasPassword: boolean;
  usesGoogle?: boolean;
}) {
  const [nameState, saveName, namePending] = useActionState(updateDisplayNameAction, initialSettingsState);
  const [passwordState, savePassword, passwordPending] = useActionState(updateAccountPasswordAction, initialSettingsState);

  return (
    <div className={styles.grid}>
      <section className={`${styles.card} ${styles.profileCard}`} aria-labelledby="profile-heading">
        <div className={styles.heading}>
          <span className={styles.icon}><UserCircle size={23} aria-hidden /></span>
          <div>
            <h2 id="profile-heading">Your profile</h2>
            <p>Your name is shared with the StressGuard app through your account.</p>
          </div>
        </div>
        <form action={saveName} className={styles.form}>
          <div className={styles.field}>
            <label htmlFor="settings-display-name">Display name</label>
            <input
              id="settings-display-name"
              name="displayName"
              type="text"
              autoComplete="name"
              defaultValue={displayName}
              maxLength={80}
              required
            />
          </div>
          <Feedback state={nameState} />
          <button disabled={namePending} type="submit">
            {namePending ? "Saving..." : "Save name"}
          </button>
        </form>
      </section>

      <section className={`${styles.card} ${styles.securityCard}`} aria-labelledby="security-heading">
        <div className={styles.heading}>
          <span className={styles.icon}><LockKey size={23} aria-hidden /></span>
          <div>
            <h2 id="security-heading">Account & security</h2>
            <p>
              {!hasPassword && usesGoogle
                ? "Google signs you in without a StressGuard password. Add one only if you also want email and password sign-in."
                : hasPassword && usesGoogle
                  ? "Google sign-in needs no password. This account also has a separate StressGuard password for email sign-in."
                  : "Manage the password used for email sign-in on web and Android."}
            </p>
          </div>
        </div>
        <div className={styles.accountEmail}>
          <span>Account email</span>
          <strong>{email}</strong>
        </div>
        <form action={savePassword} className={styles.form}>
          {hasPassword ? (
            <PasswordField id="settings-current-password" name="currentPassword" label="Current password" autoComplete="current-password" />
          ) : null}
          <PasswordField id="settings-new-password" name="password" label="New password" autoComplete="new-password" />
          <PasswordField id="settings-confirm-password" name="confirmPassword" label="Confirm new password" autoComplete="new-password" />
          <p className={styles.helper}>
            {hasPassword
              ? "Use at least 6 characters. Forgot your current StressGuard password? Sign out, then use Forgot password? on the sign-in page to reset it."
              : "Use at least 6 characters. Your Google sign-in will continue to work."}
          </p>
          <Feedback state={passwordState} />
          <button disabled={passwordPending} type="submit">
            {passwordPending ? "Updating..." : hasPassword ? "Change password" : "Set password"}
          </button>
        </form>
      </section>

      <section className={`${styles.card} ${styles.privacyCard}`} aria-labelledby="privacy-heading">
        <div className={styles.heading}>
          <span className={styles.icon}><ShieldCheck size={23} aria-hidden /></span>
          <div>
            <h2 id="privacy-heading">Privacy & your data</h2>
            <p>Your dashboard reads records stored for this signed-in account. It does not show another account’s readings.</p>
          </div>
        </div>
        <div className={styles.privacyActions}>
          <div>
            <strong>Review or export your history</strong>
            <span>Open your synchronized readings and request a CSV export.</span>
          </div>
          <Link href="/history">Open history <ArrowUpRight size={17} aria-hidden /></Link>
        </div>
      </section>
    </div>
  );
}

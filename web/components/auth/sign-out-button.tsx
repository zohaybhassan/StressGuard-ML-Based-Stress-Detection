"use client";

import { SignOut } from "@phosphor-icons/react";
import { useFormStatus } from "react-dom";

import { signOutAction } from "@/app/auth/actions";

import styles from "./sign-out-button.module.css";

function Button() {
  const { pending } = useFormStatus();
  return (
    <button
      aria-label={pending ? "Signing out" : "Sign out"}
      className={styles.button}
      disabled={pending}
      title="Sign out"
      type="submit"
    >
      <SignOut size={19} aria-hidden />
    </button>
  );
}

export function SignOutButton() {
  return (
    <form action={signOutAction}>
      <Button />
    </form>
  );
}

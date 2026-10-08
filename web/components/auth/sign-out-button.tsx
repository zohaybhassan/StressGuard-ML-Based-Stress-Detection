"use client";

import { SignOut } from "@phosphor-icons/react";
import { useFormStatus } from "react-dom";

import { signOutAction } from "@/app/auth/actions";

import styles from "./sign-out-button.module.css";

function Button() {
  const { pending } = useFormStatus();
  return (
    <button
      className={styles.button}
      disabled={pending}
      type="submit"
    >
      <SignOut size={19} aria-hidden />
      <span>{pending ? "Logging out..." : "Log out"}</span>
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

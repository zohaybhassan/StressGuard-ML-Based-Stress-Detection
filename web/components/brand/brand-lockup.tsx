import Image from "next/image";
import Link from "next/link";

import styles from "./brand-lockup.module.css";

type BrandLockupProps = {
  authTone?: boolean;
  compact?: boolean;
  href?: string;
  prominent?: boolean;
  splitTone?: boolean;
};

export function BrandLockup({
  authTone = false,
  compact = false,
  href = "/",
  prominent = false,
  splitTone = false,
}: BrandLockupProps) {
  return (
    <Link
      className={`${styles.lockup} ${prominent ? styles.prominent : ""} ${authTone ? styles.authTone : ""}`}
      href={href}
      aria-label="StressGuard home"
    >
      <Image
        className={styles.mark}
        src="/brand/stressguard-mark.png"
        alt=""
        width={360}
        height={315}
        priority
      />
      {!compact && (
        <span className={styles.wordmark}>
          {splitTone || authTone ? (
            <>
              <span className={authTone ? styles.wordmarkStressAuth : styles.wordmarkStress}>
                Stress
              </span>
              <span className={authTone ? styles.wordmarkGuardAuth : styles.wordmarkGuard}>
                Guard
              </span>
            </>
          ) : "StressGuard"}
        </span>
      )}
    </Link>
  );
}

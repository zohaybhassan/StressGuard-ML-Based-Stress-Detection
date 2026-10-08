import Image from "next/image";
import Link from "next/link";

import styles from "./brand-lockup.module.css";

type BrandLockupProps = {
  href?: string | null;
};

export function BrandLockup({
  href = "/",
}: BrandLockupProps) {
  const content = (
    <>
      <Image
        className={styles.mark}
        src="/brand/stressguard-mark.png"
        alt=""
        width={360}
        height={315}
        priority
      />
      <span className={styles.wordmark}>StressGuard</span>
    </>
  );

  return href === null ? (
    <span className={styles.lockup} aria-label="StressGuard">
      {content}
    </span>
  ) : (
    <Link className={styles.lockup} href={href} aria-label="StressGuard home">
      {content}
    </Link>
  );
}

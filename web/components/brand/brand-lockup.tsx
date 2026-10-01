import Image from "next/image";
import Link from "next/link";

import styles from "./brand-lockup.module.css";

type BrandLockupProps = {
  compact?: boolean;
  href?: string;
};

export function BrandLockup({ compact = false, href = "/" }: BrandLockupProps) {
  return (
    <Link className={styles.lockup} href={href} aria-label="StressGuard home">
      <Image
        className={styles.mark}
        src="/brand/stressguard-mark.png"
        alt=""
        width={360}
        height={315}
        priority
      />
      {!compact && <span className={styles.wordmark}>StressGuard</span>}
    </Link>
  );
}

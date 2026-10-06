"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { DASHBOARD_TIME_ZONE_COOKIE } from "@/lib/dashboard/time-zone";

function readCookie(name: string) {
  const prefix = `${name}=`;
  const cookie = document.cookie
    .split(";")
    .map((entry) => entry.trim())
    .find((entry) => entry.startsWith(prefix));

  return cookie ? decodeURIComponent(cookie.slice(prefix.length)) : null;
}

export function BrowserTimeZone() {
  const router = useRouter();

  useEffect(() => {
    const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    if (!timeZone || readCookie(DASHBOARD_TIME_ZONE_COOKIE) === timeZone) {
      return;
    }

    const secure = window.location.protocol === "https:" ? "; Secure" : "";
    document.cookie = `${DASHBOARD_TIME_ZONE_COOKIE}=${encodeURIComponent(timeZone)}; Path=/; Max-Age=31536000; SameSite=Lax${secure}`;
    router.refresh();
  }, [router]);

  return null;
}

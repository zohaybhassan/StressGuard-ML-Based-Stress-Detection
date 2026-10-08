import type { Metadata } from "next";
import { Theme } from "@radix-ui/themes";
import { GeistSans } from "geist/font/sans";
import localFont from "next/font/local";

import "./globals.css";

const openSans = localFont({
  src: "./fonts/open-sans-latin-variable.woff2",
  variable: "--font-open-sans",
  display: "swap",
  weight: "300 800",
});

export const metadata: Metadata = {
  title: "StressGuard",
  description:
    "A companion portal for reviewing stress, heart rate, sleep, and activity insights synchronized from the StressGuard Android app.",
  applicationName: "StressGuard",
  icons: {
    icon: "/brand/stressguard-mark.png",
  },
  robots: {
    index: true,
    follow: true,
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className={`${openSans.variable} ${GeistSans.variable}`} data-scroll-behavior="smooth">
      <body>
        <Theme accentColor="teal" grayColor="sage" radius="large" scaling="100%">
          {children}
        </Theme>
      </body>
    </html>
  );
}

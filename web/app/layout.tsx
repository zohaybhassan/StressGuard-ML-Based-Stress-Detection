import type { Metadata } from "next";
import { Theme } from "@radix-ui/themes";
import { GeistSans } from "geist/font/sans";

import "./globals.css";

export const metadata: Metadata = {
  title: {
    default: "StressGuard | Understand your stress patterns",
    template: "%s | StressGuard",
  },
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
    <html lang="en" className={GeistSans.variable} data-scroll-behavior="smooth">
      <body>
        <Theme accentColor="teal" grayColor="sage" radius="large" scaling="100%">
          {children}
        </Theme>
      </body>
    </html>
  );
}

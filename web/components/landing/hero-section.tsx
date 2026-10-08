"use client";

import {
  ChartBar,
  Heart,
  Leaf,
} from "@phosphor-icons/react/dist/ssr";
import Link from "next/link";
import { useEffect, useRef } from "react";

import { ProductPreview } from "./product-preview";
import styles from "./landing-reference.module.css";

function GooglePlayMark() {
  return (
    <svg width="19" height="20" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path fill="#20C8F7" d="M3.1 2.2v19.6L13.5 12 3.1 2.2Z" />
      <path fill="#26C968" d="M3.1 2.2 13.5 12l3.1-3.1L5.4 2.4a2.2 2.2 0 0 0-2.3-.2Z" />
      <path fill="#FFD148" d="m16.6 8.9 3.6 2.1c.9.5.9 1.5 0 2l-3.6 2.1-3.1-3.1 3.1-3.1Z" />
      <path fill="#F55E57" d="m13.5 12 3.1 3.1-11.2 6.5a2.2 2.2 0 0 1-2.3.2L13.5 12Z" />
    </svg>
  );
}

const benefits = [
  { icon: Heart, label: "Feel better every day" },
  { icon: ChartBar, label: "Data-driven insights" },
  { icon: Leaf, label: "A calmer, healthier you" },
];

export function HeroSection() {
  const heroRef = useRef<HTMLElement>(null);

  useEffect(() => {
    const hero = heroRef.current;
    if (!hero || typeof window.matchMedia !== "function" || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;

    let frame = 0;
    const replay = () => {
      if (document.visibilityState !== "visible") return;
      const parts = hero.querySelectorAll<HTMLElement>("[data-hero-reveal]");
      parts.forEach((part) => { part.style.animation = "none"; });
      // Restart the one-time CSS sequence after a background tab or page restore.
      void hero.offsetWidth;
      parts.forEach((part) => { part.style.removeProperty("animation"); });
    };
    const onVisible = () => {
      if (document.visibilityState === "visible") frame = requestAnimationFrame(replay);
    };
    const onPageShow = (event: PageTransitionEvent) => {
      if (event.persisted) frame = requestAnimationFrame(replay);
    };

    document.addEventListener("visibilitychange", onVisible);
    window.addEventListener("pageshow", onPageShow);
    return () => {
      cancelAnimationFrame(frame);
      document.removeEventListener("visibilitychange", onVisible);
      window.removeEventListener("pageshow", onPageShow);
    };
  }, []);

  return (
    <section ref={heroRef} id="home" className={styles.hero}>
      <div className={"page-container " + styles.heroGrid}>
        <div className={styles.heroCopy}>
          <div className={styles.heroHeadingGroup}>
            <p className={styles.heroEyebrow} data-hero-reveal="eyebrow">
              Your wellness companion
            </p>
            <h1 className={styles.heroTitle} aria-label="Stress Less. Understand More.">
              <span className={styles.heroTitleLine} data-hero-reveal="title-one">
                Stress Less.
              </span>
              <span className={styles.heroTitleLine} data-hero-reveal="title-two">
                Understand More.
              </span>
            </h1>
          </div>
          <p className={"body-copy " + styles.heroBody} data-hero-reveal="body">
            AI-powered stress insights from your wearable data. Track your body, understand what it
            means, and get personalized guidance for a calmer, healthier you.
          </p>
          <div className={styles.heroActions} data-hero-reveal="actions">
            <Link
              className={"button-secondary " + styles.playStoreCta}
              href="https://play.google.com/store/search?q=StressGuard&c=apps"
              target="_blank"
              rel="noreferrer"
            >
              <GooglePlayMark />
              Get it on Play Store
            </Link>
          </div>
          <div className={styles.benefits}>
            {benefits.map(({ icon: Icon, label }, index) => (
              <div key={label} data-hero-reveal={`benefit-${index + 1}`}>
                <span>
                  <Icon size={20} weight="duotone" aria-hidden />
                </span>
                {label}
              </div>
            ))}
          </div>
        </div>
        <ProductPreview />
      </div>
    </section>
  );
}

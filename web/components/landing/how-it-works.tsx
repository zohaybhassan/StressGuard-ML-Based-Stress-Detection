"use client";

import Image from "next/image";
import { useEffect, useRef, useState } from "react";

import styles from "./landing-reference.module.css";

const steps = [
  {
    title: "Connect your watch",
    description:
      "Pair your Samsung/Wear OS watch with StressGuard and securely sync your health signals through the Android app.",
    image: "/brand/hero-smartwatch-v5-three-quarter.png",
  },
  {
    title: "Track body signals",
    description:
      "Monitor heart rate, sleep, steps, activity, and stress-related inputs throughout your day.",
    image: "/brand/how-track-signals-soft-3d.png",
  },
  {
    title: "Detect stress patterns",
    description:
      "StressGuard analyzes synchronized signals to identify stress patterns and changing trends.",
    image: "/brand/how-detect-patterns-soft-3d.png",
  },
  {
    title: "Get personalized guidance",
    description:
      "Receive useful insights, AI-supported guidance, and recommendations based on your available data.",
    image: "/brand/how-guidance-soft-3d.png",
  },
  {
    title: "Build healthier habits",
    description:
      "Use breathing sessions, workout tracking, and long-term trends to support healthier routines.",
    image: "/brand/how-habits-soft-3d.png",
  },
];

export function HowItWorks() {
  const sectionRef = useRef<HTMLElement>(null);
  const [isRevealed, setIsRevealed] = useState(false);
  const [motionReady, setMotionReady] = useState(false);
  const [revealedSteps, setRevealedSteps] = useState<Set<number>>(() => new Set());

  useEffect(() => {
    const section = sectionRef.current;
    if (!section || typeof IntersectionObserver === "undefined" || typeof window.matchMedia !== "function" || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;

    setMotionReady(true);

    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue;
          const step = Number((entry.target as HTMLElement).dataset.step);
          setRevealedSteps((previous) => new Set(previous).add(step));
          setIsRevealed(true);
          observer.unobserve(entry.target);
        }
      },
      { threshold: 0.2, rootMargin: "0px 0px -8% 0px" },
    );

    section.querySelectorAll<HTMLElement>("[data-step]").forEach((step) => observer.observe(step));
    return () => observer.disconnect();
  }, []);

  return (
    <section ref={sectionRef} id="how-it-works" className={styles.howSection}>
      <div className={"page-container " + styles.howGrid}>
        <div className={styles.howIntro}>
          <h2 className={styles.sectionTitle}>How It Works</h2>
          <p className="body-copy">
            Get started in minutes and unlock a calmer, more balanced you.
          </p>
        </div>
        <ol className={styles.journeyTrack} data-animate={motionReady} data-revealed={isRevealed}>
          {steps.map(({ title, description, image }, index) => (
            <li key={title} className={styles.journeyStep} data-step={index + 1} data-revealed={revealedSteps.has(index + 1)}>
              <div className={styles.stepVisual}>
                <span className={styles.stepNumber} aria-hidden>
                  {index + 1}
                </span>
                <div className={styles.visualHalo} aria-hidden />
                <Image
                  className={styles.stepImage}
                  src={image}
                  alt=""
                  width={320}
                  height={320}
                  sizes="(max-width: 620px) 42vw, (max-width: 1023px) 28vw, 17vw"
                />
              </div>
              <div className={styles.stepCopy}>
                <h3>{title}</h3>
                <p>{description}</p>
              </div>
            </li>
          ))}
        </ol>
      </div>
    </section>
  );
}

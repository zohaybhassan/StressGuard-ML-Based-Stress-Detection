"use client";

import { ChartLineUp, Devices, Heart } from "@phosphor-icons/react";
import Image from "next/image";
import { useEffect, useRef, useState } from "react";

import styles from "./landing-reference.module.css";

export function FinalCta() {
  const sectionRef = useRef<HTMLElement>(null);
  const [isRevealed, setIsRevealed] = useState(false);

  useEffect(() => {
    const section = sectionRef.current;
    if (!section) return;

    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setIsRevealed(true);
          observer.disconnect();
        }
      },
      { threshold: 0.25, rootMargin: "0px 0px -8% 0px" },
    );

    observer.observe(section);
    return () => observer.disconnect();
  }, []);

  return (
    <section
      ref={sectionRef}
      id="about"
      className={styles.finalSection}
      data-revealed={isRevealed}
    >
      <div className={styles.finalPanel}>
        <div className={styles.finalImage}>
          <Image
            src="/brand/wellness-journey.jpg"
            alt="A hiker pausing beside a mountain lake"
            fill
            sizes="(max-width: 620px) 100vw, 42vw"
          />
        </div>
        <div className={styles.finalContent}>
          <div className={styles.finalCopy}>
            <p className={styles.finalEyebrow}>About StressGuard</p>
            <h2 className={styles.finalTitle}>Wellness, made clearer.</h2>
            <div className={styles.aboutText}>
              <p>
                StressGuard brings stress, heart rate, sleep, and activity information from your
                wearable into one clear experience. Your phone and watch help you check in during
                the day; the web dashboard helps you look back and understand your patterns.
              </p>
              <p>
                We turn everyday signals into understandable insights and supportive guidance, so
                you can make more informed choices for your well-being. StressGuard supports wellness
                awareness. It does not diagnose medical conditions or replace professional care.
              </p>
            </div>
          </div>
          <div className={styles.finalBenefits}>
            <span>
              <i><ChartLineUp size={20} weight="duotone" aria-hidden /></i>
              <strong>See your patterns</strong>
              <small>Understand what changes</small>
            </span>
            <span>
              <i><Devices size={20} weight="duotone" aria-hidden /></i>
              <strong>Stay connected</strong>
              <small>Phone, watch, and web</small>
            </span>
            <span>
              <i><Heart size={20} weight="duotone" aria-hidden /></i>
              <strong>Feel supported</strong>
              <small>Guidance without alarm</small>
            </span>
          </div>
        </div>
      </div>
    </section>
  );
}

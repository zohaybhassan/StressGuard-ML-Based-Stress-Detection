"use client";

import {
  ArrowRight,
  ChartBar,
  Heart,
  Leaf,
} from "@phosphor-icons/react";
import Image from "next/image";
import Link from "next/link";
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
            <p className={styles.finalEyebrow}>A calmer, healthier you</p>
            <h2 className={styles.finalTitle}>Start Your Wellness Journey Today</h2>
            <p className="body-copy">
              Join people using StressGuard to understand their stress, build healthier habits, and
              feel better every day.
            </p>
            <div className={styles.finalActions}>
              <Link className="button-primary" href="/auth?mode=register">
                Get Started
                <ArrowRight size={17} weight="bold" aria-hidden />
              </Link>
            </div>
          </div>
          <div className={styles.finalBenefits}>
            <span>
              <i><Leaf size={20} weight="duotone" aria-hidden /></i>
              <strong>Less stress</strong>
              <small>More clarity</small>
            </span>
            <span>
              <i><Heart size={20} weight="duotone" aria-hidden /></i>
              <strong>Better sleep</strong>
              <small>Brighter days</small>
            </span>
            <span>
              <i><ChartBar size={20} weight="duotone" aria-hidden /></i>
              <strong>Small insights</strong>
              <small>Big changes</small>
            </span>
          </div>
        </div>
      </div>
    </section>
  );
}

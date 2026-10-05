import { FeatureGrid } from "@/components/landing/feature-grid";
import { FinalCta } from "@/components/landing/final-cta";
import { HeroSection } from "@/components/landing/hero-section";
import { HowItWorks } from "@/components/landing/how-it-works";
import { SiteFooter } from "@/components/landing/site-footer";
import { VitalsPreview } from "@/components/landing/vitals-preview";
import { PublicNavigation } from "@/components/layout/public-navigation";
import styles from "@/components/landing/landing-reference.module.css";

export default function LandingPage() {
  return (
    <div className={styles.landingShell}>
      <PublicNavigation />
      <main>
        <HeroSection />
        <FeatureGrid />
        <HowItWorks />
        <VitalsPreview />
        <FinalCta />
      </main>
      <SiteFooter />
    </div>
  );
}

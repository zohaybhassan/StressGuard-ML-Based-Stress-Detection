import { FeatureGrid } from "@/components/landing/feature-grid";
import { FinalCta } from "@/components/landing/final-cta";
import { HeroSection } from "@/components/landing/hero-section";
import { HowItWorks } from "@/components/landing/how-it-works";
import { SiteFooter } from "@/components/landing/site-footer";
import { VitalsPreview } from "@/components/landing/vitals-preview";
import { PublicNavigation } from "@/components/layout/public-navigation";

export default function LandingPage() {
  return (
    <>
      <PublicNavigation />
      <main>
        <HeroSection />
        <FeatureGrid />
        <HowItWorks />
        <VitalsPreview />
        <FinalCta />
      </main>
      <SiteFooter />
    </>
  );
}

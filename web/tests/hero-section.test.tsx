import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { HeroSection } from "@/components/landing/hero-section";

describe("HeroSection", () => {
  it("states the product value without making a diagnostic claim", () => {
    render(<HeroSection />);

    expect(
      screen.getByRole("heading", { name: "Stress less. Understand more." }),
    ).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /get started/i })).toHaveAttribute(
      "href",
      "/auth?mode=register",
    );
    expect(screen.queryByText(/diagnose/i)).not.toBeInTheDocument();
  });
});

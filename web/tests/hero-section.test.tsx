import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { HeroSection } from "@/components/landing/hero-section";

describe("HeroSection", () => {
  it("states the product value without making a diagnostic claim", () => {
    render(<HeroSection />);

    expect(
      screen.getByRole("heading", { name: "Stress Less. Understand More." }),
    ).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /get started/i })).toHaveAttribute(
      "href",
      "/auth?mode=register",
    );
    expect(screen.getByRole("link", { name: /get it on google play/i })).toHaveAttribute(
      "href",
      "https://play.google.com/store/search?q=StressGuard&c=apps",
    );
    expect(screen.queryByRole("link", { name: /view demo/i })).not.toBeInTheDocument();
    expect(screen.getByText("Ethan Carter")).toBeInTheDocument();
    expect(screen.queryByText("Fahad Saleem")).not.toBeInTheDocument();
    expect(screen.queryByText(/diagnose/i)).not.toBeInTheDocument();
  });
});

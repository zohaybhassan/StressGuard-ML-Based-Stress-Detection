import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { PublicNavigation } from "@/components/layout/public-navigation";

afterEach(cleanup);

describe("public page chrome", () => {
  it("keeps the requested header links and account actions", () => {
    render(<PublicNavigation />);

    expect(screen.getByRole("navigation", { name: "Main navigation" })).toHaveTextContent(
      "HomeFeaturesHow It WorksTrendsAbout",
    );
    for (const link of screen.getAllByRole("link", { name: "Home" })) {
      expect(link).toHaveAttribute("href", "/#home");
    }
    expect(screen.getByLabelText("StressGuard")).not.toHaveAttribute("href");
    expect(screen.queryByRole("link", { name: "Assistant" })).not.toBeInTheDocument();
    for (const link of screen.getAllByRole("link", { name: "Sign in" })) {
      expect(link).toHaveAttribute("href", "/auth");
    }
    for (const link of screen.getAllByRole("link", { name: /Get Started/i })) {
      expect(link).toHaveAttribute("href", "/auth?mode=register");
    }
  });

});

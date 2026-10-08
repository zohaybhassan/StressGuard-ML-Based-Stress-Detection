import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { BrandLockup } from "@/components/brand/brand-lockup";

afterEach(cleanup);

describe("BrandLockup", () => {
  it("keeps the public brand linked to the home page", () => {
    render(<BrandLockup />);
    expect(screen.getByRole("link", { name: "StressGuard home" })).toHaveAttribute(
      "href",
      "/",
    );
  });

  it("renders a noninteractive brand inside the signed-in portal", () => {
    render(<BrandLockup href={null} />);
    expect(screen.queryByRole("link", { name: "StressGuard home" })).not.toBeInTheDocument();
    expect(screen.getByText("StressGuard")).toBeInTheDocument();
  });
});

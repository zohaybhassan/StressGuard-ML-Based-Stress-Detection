import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { AuthForm } from "@/app/auth/auth-form";

vi.mock("@/app/auth/actions", () => ({
  forgotPasswordAction: vi.fn(),
  initialAuthState: { status: "idle" },
  registerAction: vi.fn(),
  resetPasswordAction: vi.fn(),
  setGooglePasswordAction: vi.fn(),
  signInAction: vi.fn(),
  startGoogleOAuthAction: vi.fn(),
}));

describe("AuthForm", () => {
  it("renders the complete email registration form", () => {
    render(
      <AuthForm configured mode="register" sessionAvailable={false} />,
    );

    expect(screen.getByRole("heading", { name: "Create your account" })).toBeInTheDocument();
    expect(screen.getByLabelText("Email address")).toBeInTheDocument();
    expect(screen.getByLabelText("New password")).toHaveAttribute("minLength", "6");
    expect(screen.getByLabelText("Confirm password")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Continue with Google" })).toBeEnabled();
  });

  it("explains when a reset session has expired", () => {
    render(<AuthForm configured mode="reset" sessionAvailable={false} />);

    expect(screen.getByText("This secure session is no longer active.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /request a new reset link/i })).toHaveAttribute(
      "href",
      "/auth?mode=forgot",
    );
  });
});

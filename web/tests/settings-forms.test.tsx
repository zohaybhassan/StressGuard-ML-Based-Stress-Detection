import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SettingsForms } from "@/app/(protected)/settings/settings-forms";

vi.mock("@/app/(protected)/settings/actions", () => ({
  updateDisplayNameAction: vi.fn(),
  updateAccountPasswordAction: vi.fn(),
}));

afterEach(cleanup);

describe("Settings password visibility", () => {
  it("toggles current, new and confirmation fields independently", () => {
    render(<SettingsForms displayName="Zohaib" email="user@example.com" hasPassword />);

    const current = screen.getByLabelText("Current password");
    const next = screen.getByLabelText("New password");
    const confirm = screen.getByLabelText("Confirm new password");
    expect(current).toHaveAttribute("type", "password");
    expect(next).toHaveAttribute("type", "password");
    expect(confirm).toHaveAttribute("type", "password");

    const showCurrent = screen.getByRole("button", { name: "Show current password" });
    expect(showCurrent).toHaveAttribute("type", "button");
    fireEvent.click(showCurrent);
    expect(current).toHaveAttribute("type", "text");
    expect(next).toHaveAttribute("type", "password");

    fireEvent.click(screen.getByRole("button", { name: "Show new password" }));
    fireEvent.click(screen.getByRole("button", { name: "Show confirm new password" }));
    expect(next).toHaveAttribute("type", "text");
    expect(confirm).toHaveAttribute("type", "text");

    fireEvent.click(screen.getByRole("button", { name: "Hide current password" }));
    expect(current).toHaveAttribute("type", "password");
  });

  it("does not ask a Google-only user for a password they never set", () => {
    render(<SettingsForms displayName="Zohaib" email="user@example.com" hasPassword={false} usesGoogle />);
    expect(screen.queryByLabelText("Current password")).not.toBeInTheDocument();
    expect(screen.getByText(/Google signs you in without a StressGuard password/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Set password" })).toBeInTheDocument();
  });
});

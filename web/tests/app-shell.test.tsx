import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({ usePathname: () => "/dashboard" }));
vi.mock("@/components/auth/sign-out-button", () => ({
  SignOutButton: () => <button type="button">Log out</button>,
}));

import { AppShell } from "@/components/layout/app-shell";

afterEach(cleanup);

describe("protected account menu", () => {
  it("opens the profile dropdown with a working settings destination", () => {
    render(<AppShell email="member@example.com" displayName="Maya Khan"><p>Dashboard</p></AppShell>);

    expect(screen.queryByRole("group", { name: "Account options" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Account menu for Maya Khan" }));

    const menu = screen.getByRole("group", { name: "Account options" });
    expect(within(menu).getByText("Maya Khan")).toBeInTheDocument();
    expect(within(menu).getByText("member@example.com")).toBeInTheDocument();
    expect(within(menu).getByRole("link", { name: "Settings" })).toHaveAttribute("href", "/settings");
    expect(screen.queryByText("Android connected")).not.toBeInTheDocument();
    expect(screen.getAllByText("New app readings appear here when you reload this page.")).toHaveLength(2);
  });
});

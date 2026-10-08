import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  cookies: vi.fn(),
  createClient: vi.fn(),
  deleteCookie: vi.fn(),
  redirect: vi.fn(),
  revalidatePath: vi.fn(),
  signOut: vi.fn(),
}));

vi.mock("next/headers", () => ({ cookies: mocks.cookies }));
vi.mock("next/cache", () => ({ revalidatePath: mocks.revalidatePath }));
vi.mock("next/navigation", () => ({
  redirect: mocks.redirect,
  RedirectType: { replace: "replace" },
}));
vi.mock("@/lib/supabase/server", () => ({
  createSupabaseServerClient: mocks.createClient,
}));

import { signOutAction } from "@/app/auth/actions";

describe("sign out action", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.createClient.mockResolvedValue({ auth: { signOut: mocks.signOut } });
    mocks.cookies.mockResolvedValue({
      delete: mocks.deleteCookie,
      getAll: () => [
        { name: "sb-projectref-auth-token.0", value: "private" },
        { name: "sb-projectref-auth-token.1", value: "private" },
        { name: "sg-password-recovery", value: "active" },
        { name: "stressguard-time-zone", value: "Asia/Karachi" },
      ],
    });
    mocks.redirect.mockImplementation(() => {
      throw new Error("NEXT_REDIRECT");
    });
  });

  it("clears this browser's session and invalidates protected layouts", async () => {
    mocks.signOut.mockResolvedValue({ error: null });

    await expect(signOutAction()).rejects.toThrow("NEXT_REDIRECT");

    expect(mocks.signOut).toHaveBeenCalledWith({ scope: "local" });
    expect(mocks.deleteCookie).toHaveBeenCalledWith("sb-projectref-auth-token.0");
    expect(mocks.deleteCookie).toHaveBeenCalledWith("sb-projectref-auth-token.1");
    expect(mocks.deleteCookie).toHaveBeenCalledWith("sg-password-recovery");
    expect(mocks.deleteCookie).not.toHaveBeenCalledWith("stressguard-time-zone");
    expect(mocks.revalidatePath).toHaveBeenCalledWith("/", "layout");
    expect(mocks.redirect).toHaveBeenCalledWith("/auth?status=signed-out", "replace");
  });

  it("clears local credentials even when Supabase sign-out fails", async () => {
    mocks.signOut.mockRejectedValue(new Error("network unavailable"));

    await expect(signOutAction()).rejects.toThrow("NEXT_REDIRECT");

    expect(mocks.deleteCookie).toHaveBeenCalledWith("sb-projectref-auth-token.0");
    expect(mocks.deleteCookie).toHaveBeenCalledWith("sg-password-recovery");
    expect(mocks.redirect).toHaveBeenCalled();
  });
});

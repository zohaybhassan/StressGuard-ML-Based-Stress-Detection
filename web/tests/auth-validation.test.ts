import { describe, expect, it } from "vitest";

import {
  forgotPasswordSchema,
  newPasswordSchema,
  registerSchema,
  signInSchema,
} from "@/lib/auth/validation";

describe("auth validation", () => {
  it("keeps the Android minimum password length of six characters", () => {
    expect(
      registerSchema.safeParse({
        email: "person@example.com",
        password: "12345",
        confirmPassword: "12345",
      }).success,
    ).toBe(false);
    expect(
      registerSchema.safeParse({
        email: "person@example.com",
        password: "123456",
        confirmPassword: "123456",
      }).success,
    ).toBe(true);
  });

  it("places a mismatch error on the confirmation field", () => {
    const result = newPasswordSchema.safeParse({
      password: "healthy-password",
      confirmPassword: "different-password",
    });

    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.flatten().fieldErrors.confirmPassword).toContain(
        "Passwords do not match.",
      );
    }
  });

  it("validates email syntax before auth requests", () => {
    expect(forgotPasswordSchema.safeParse({ email: "not-an-email" }).success).toBe(false);
    expect(
      signInSchema.safeParse({ email: "person@example.com", password: "secret" }).success,
    ).toBe(true);
  });
});

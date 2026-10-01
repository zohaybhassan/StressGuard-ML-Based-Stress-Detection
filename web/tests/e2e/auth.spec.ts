import { expect, test } from "@playwright/test";

test("signed-out visitors are redirected away from protected routes", async ({ page }) => {
  await page.goto("/dashboard");

  await expect(page).toHaveURL((url) => url.pathname === "/auth" && url.searchParams.get("reason") === "session");
  await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();
});

test("sign-in page renders the secure account options", async ({ page }) => {
  await page.goto("/auth");

  await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();
  await expect(page.getByLabel("Email address")).toBeVisible();
  await expect(page.getByLabel("Password", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Continue with Google" })).toBeVisible();
});

test("registration validates matching passwords before contacting Supabase", async ({ page }) => {
  await page.goto("/auth?mode=register");

  await page.getByLabel("Email address").fill("student@example.com");
  await page.getByLabel("New password", { exact: true }).fill("secure-one");
  await page.getByLabel("Confirm password", { exact: true }).fill("secure-two");
  await page.getByRole("button", { name: "Create account" }).click();

  await expect(page.getByText("Passwords do not match.")).toBeVisible();
});

test("forgot-password state keeps account discovery feedback generic", async ({ page }) => {
  await page.goto("/auth");
  await page.getByRole("link", { name: "Forgot password?" }).click();

  await expect(page.getByRole("heading", { name: "Reset your password" })).toBeVisible();
  await page.getByLabel("Email address").fill("not-an-email");
  await page.getByRole("button", { name: "Send reset link" }).click();
  await expect(page.getByText("Enter a valid email address.")).toBeVisible();
});

test("authenticated visitors leave the auth page when a safe session fixture is supplied", async ({
  page,
}) => {
  test.skip(
    !process.env.E2E_AUTH_STORAGE_STATE,
    "Provide E2E_AUTH_STORAGE_STATE for an authenticated Supabase integration run.",
  );

  await page.goto("/auth");
  await expect(page).toHaveURL((url) => url.pathname === "/dashboard");
});

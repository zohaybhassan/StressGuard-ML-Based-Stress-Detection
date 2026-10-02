import { expect, test } from "@playwright/test";

test("signed-out visitors are redirected away from History", async ({ page }) => {
  await page.goto("/history");
  await expect(page).toHaveURL(/\/auth(?:\?|$)/);
  await expect(page.getByRole("heading", { name: /welcome back/i })).toBeVisible();
});

test.describe("authenticated History", () => {
  test.use({ storageState: process.env.E2E_AUTH_STORAGE_STATE ?? { cookies: [], origins: [] } });
  test.beforeEach(() => test.skip(!process.env.E2E_AUTH_STORAGE_STATE, "Set E2E_AUTH_STORAGE_STATE to a safe authenticated test account."));

  test("shows tabs, filters, pagination or empty state, and export controls on mobile", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto("/history");
    await expect(page.getByRole("heading", { name: "History", exact: true })).toBeVisible();
    await expect(page.getByRole("link", { name: "Alerts / Feedback" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Prepare CSV" })).toBeVisible();
    const bodyWidth = await page.locator("body").evaluate((element) => element.scrollWidth);
    expect(bodyWidth).toBeLessThanOrEqual(390);
  });
});

test.describe("empty History account", () => {
  test.use({ storageState: process.env.E2E_EMPTY_ACCOUNT_STORAGE_STATE ?? { cookies: [], origins: [] } });
  test.beforeEach(() => test.skip(!process.env.E2E_EMPTY_ACCOUNT_STORAGE_STATE, "Set E2E_EMPTY_ACCOUNT_STORAGE_STATE to an empty authenticated account."));
  test("shows an honest empty state", async ({ page }) => {
    await page.goto("/history");
    await expect(page.getByRole("heading", { name: "No records match these filters" })).toBeVisible();
  });
});

import { expect, test } from "@playwright/test";

test("signed-out visitors are redirected away from Trends without console errors", async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });

  await page.goto("/trends");

  await expect(page).toHaveURL(/\/auth(?:\?|$)/);
  await expect(page.getByRole("heading", { name: /welcome back/i })).toBeVisible();
  expect(consoleErrors).toEqual([]);
});

test.describe("authenticated Trends", () => {
  test.use({
    storageState: process.env.E2E_AUTH_STORAGE_STATE ?? { cookies: [], origins: [] },
  });

  test.beforeEach(() => {
    test.skip(
      !process.env.E2E_AUTH_STORAGE_STATE,
      "Set E2E_AUTH_STORAGE_STATE to a safe authenticated test account.",
    );
  });

  test("renders both ranges and remains responsive", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto("/trends");

    await expect(page.getByRole("heading", { name: "Trends", exact: true })).toBeVisible();
    await expect(page.getByRole("link", { name: "7 Days" })).toBeVisible();
    await expect(page.getByRole("link", { name: "30 Days" })).toBeVisible();

    await page.getByRole("link", { name: "30 Days" }).click();
    await expect(page).toHaveURL(/\/trends\?range=30$/);
    await expect(page.getByRole("link", { name: "30 Days" })).toHaveAttribute("aria-current", "page");

    const bodyWidth = await page.locator("body").evaluate((element) => element.scrollWidth);
    expect(bodyWidth).toBeLessThanOrEqual(390);
  });
});

test.describe("empty Trends account", () => {
  test.use({
    storageState: process.env.E2E_EMPTY_ACCOUNT_STORAGE_STATE ?? { cookies: [], origins: [] },
  });

  test.beforeEach(() => {
    test.skip(
      !process.env.E2E_EMPTY_ACCOUNT_STORAGE_STATE,
      "Set E2E_EMPTY_ACCOUNT_STORAGE_STATE to an empty authenticated account.",
    );
  });

  test("shows the no-predictions explanation", async ({ page }) => {
    await page.goto("/trends");
    await expect(page.getByRole("heading", { name: "No predictions in this range" })).toBeVisible();
    await expect(page.getByText("Missing days are not treated as zero-stress days.")).toBeVisible();
  });
});

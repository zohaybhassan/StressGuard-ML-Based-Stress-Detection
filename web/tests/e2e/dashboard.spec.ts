import { expect, test } from "@playwright/test";

test("signed-out visitors are redirected away from the dashboard", async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") {
      consoleErrors.push(message.text());
    }
  });

  await page.goto("/dashboard");

  await expect(page).toHaveURL(/\/auth(?:\?|$)/);
  await expect(page.getByRole("heading", { name: /welcome back/i })).toBeVisible();
  expect(consoleErrors).toEqual([]);
});

test.describe("authenticated dashboard", () => {
  test.use({
    storageState: process.env.E2E_AUTH_STORAGE_STATE ?? {
      cookies: [],
      origins: [],
    },
  });

  test.beforeEach(() => {
    test.skip(
      !process.env.E2E_AUTH_STORAGE_STATE,
      "Set E2E_AUTH_STORAGE_STATE to a safe test-account storage-state file.",
    );
  });

  test("renders the protected health overview responsively", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto("/dashboard");

    await expect(page.getByRole("heading", { name: /good (morning|afternoon|evening)/i })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Stress overview" })).toBeVisible();
    await expect(page.getByText("Your wearable works through the mobile app")).toBeVisible();

    const bodyWidth = await page.locator("body").evaluate((element) => element.scrollWidth);
    expect(bodyWidth).toBeLessThanOrEqual(390);
  });
});

test.describe("empty-account dashboard", () => {
  test.use({
    storageState: process.env.E2E_EMPTY_ACCOUNT_STORAGE_STATE ?? {
      cookies: [],
      origins: [],
    },
  });

  test.beforeEach(() => {
    test.skip(
      !process.env.E2E_EMPTY_ACCOUNT_STORAGE_STATE,
      "Set E2E_EMPTY_ACCOUNT_STORAGE_STATE to an empty test account.",
    );
  });

  test("shows explicit empty states without fabricated measurements", async ({ page }) => {
    await page.goto("/dashboard");

    await expect(page.getByText("No stress readings yet")).toBeVisible();
    await expect(page.getByText("No readings today")).toBeVisible();
    await expect(page.getByText("No alerts recorded")).toBeVisible();
    await expect(page.getByText("No workouts synchronized")).toBeVisible();
  });
});

import { expect, test } from "@playwright/test";

test("landing page presents the approved product story and responsive controls", async ({
  page,
}) => {
  const browserMessages: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error" || message.type() === "warning") {
      browserMessages.push(message.text());
    }
  });

  await page.goto("/");

  await expect(
    page.getByRole("heading", { name: "Stress less. Understand more." }),
  ).toBeVisible();
  await expect(page.getByText("Illustrative preview")).toBeVisible();
  await expect(page.locator("svg.recharts-surface")).toHaveCount(4);

  const monthButton = page.getByRole("button", { name: "Month" });
  await monthButton.click();
  await expect(monthButton).toHaveAttribute("aria-pressed", "true");

  const viewport = page.viewportSize();
  if (viewport && viewport.width <= 800) {
    await page.getByLabel("Open navigation").click();
    await expect(page.getByRole("navigation", { name: "Mobile navigation" })).toBeVisible();
  }

  const hasHorizontalOverflow = await page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
  );
  expect(hasHorizontalOverflow).toBe(false);
  expect(browserMessages).toEqual([]);
});

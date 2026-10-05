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
    page.getByRole("heading", { name: "Stress Less. Understand More." }),
  ).toBeVisible();
  await expect(page.getByRole("heading", { name: "Live Stress Tracking" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Breathing Exercise" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "How It Works" })).toBeVisible();
  await expect(
    page.getByText(
      "StressGuard brings together stress insights, vitals, guidance, workouts, and calming tools in one place.",
    ),
  ).toHaveCount(0);
  await expect(page.locator("#features article")).toHaveCount(6);
  await expect(page.locator("#how-it-works li")).toHaveCount(5);
  await expect(page.locator("#how-it-works img")).toHaveCount(5);
  const journeySources = await page.locator("#how-it-works img").evaluateAll((images) =>
    images.map((image) => new URL((image as HTMLImageElement).src).pathname),
  );
  expect(journeySources).toEqual([
    "/_next/image",
    "/_next/image",
    "/_next/image",
    "/_next/image",
    "/_next/image",
  ]);
  const optimizedJourneySources = await page.locator("#how-it-works img").evaluateAll((images) =>
    images.map((image) => decodeURIComponent(new URL((image as HTMLImageElement).src).searchParams.get("url") ?? "")),
  );
  expect(optimizedJourneySources).toEqual([
    "/brand/hero-smartwatch-v5-three-quarter.png",
    "/brand/how-track-signals-soft-3d.png",
    "/brand/how-detect-patterns-soft-3d.png",
    "/brand/how-guidance-soft-3d.png",
    "/brand/how-habits-soft-3d.png",
  ]);
  await expect(page.getByRole("heading", { name: "Connect your watch" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Detect stress patterns" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Build healthier habits" })).toBeVisible();
  await expect(page.locator("svg.recharts-surface")).toHaveCount(4);
  await expect(page.locator("#health-preview article")).toHaveCount(4);
  await expect(page.getByRole("heading", { name: "Start Your Wellness Journey Today" })).toBeVisible();
  await expect(page.locator("#about a")).toHaveCount(1);
  await expect(page.getByRole("link", { name: "View Demo" })).toHaveCount(0);

  const monthButton = page.getByRole("button", { name: "Month" });
  await monthButton.click();
  await expect(monthButton).toHaveAttribute("aria-pressed", "true");

  const viewport = page.viewportSize();
  if (viewport && viewport.width >= 1024) {
    const chapterLayout = await page.evaluate(() => {
      const headerHeight = document.querySelector("header")?.getBoundingClientRect().height ?? 0;
      const sectionHeights = [
        document.querySelector("main section"),
        document.querySelector("#features"),
        document.querySelector("#how-it-works"),
        document.querySelector("#health-preview"),
        document.querySelector("#about"),
      ].map((section) => section?.getBoundingClientRect().height ?? 0);

      return {
        availableHeight: window.innerHeight - headerHeight,
        sectionHeights,
      };
    });

    for (const sectionHeight of chapterLayout.sectionHeights) {
      expect(sectionHeight).toBeGreaterThanOrEqual(chapterLayout.availableHeight - 1);
    }

    const featureCardBoxes = await page.locator("#features article").evaluateAll((cards) =>
      cards.map((card) => {
        const box = card.getBoundingClientRect();
        return { width: Math.round(box.width), height: Math.round(box.height) };
      }),
    );
    expect(new Set(featureCardBoxes.map(({ width }) => width)).size).toBe(1);
    expect(new Set(featureCardBoxes.map(({ height }) => height)).size).toBe(1);

    const vitalCardBoxes = await page.locator("#health-preview article").evaluateAll((cards) =>
      cards.map((card) => ({
        width: (card as HTMLElement).offsetWidth,
        height: (card as HTMLElement).offsetHeight,
      })),
    );
    const vitalWidths = vitalCardBoxes.map(({ width }) => width);
    const vitalHeights = vitalCardBoxes.map(({ height }) => height);
    expect(Math.max(...vitalWidths) - Math.min(...vitalWidths)).toBeLessThanOrEqual(1);
    expect(Math.max(...vitalHeights) - Math.min(...vitalHeights)).toBeLessThanOrEqual(1);

    const finalComposition = await page.locator("#about").evaluate((section) => {
      const sectionBox = section.getBoundingClientRect();
      const imageBox = section.querySelector("img")?.parentElement?.getBoundingClientRect();
      return {
        imageRatio: imageBox ? imageBox.width / sectionBox.width : 0,
        topDifference: imageBox ? Math.abs(imageBox.top - sectionBox.top) : Infinity,
        bottomDifference: imageBox ? Math.abs(imageBox.bottom - sectionBox.bottom) : Infinity,
      };
    });
    expect(finalComposition.imageRatio).toBeGreaterThan(0.4);
    expect(finalComposition.imageRatio).toBeLessThan(0.44);
    expect(finalComposition.topDifference).toBeLessThanOrEqual(1);
    expect(finalComposition.bottomDifference).toBeLessThanOrEqual(1);
  }

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

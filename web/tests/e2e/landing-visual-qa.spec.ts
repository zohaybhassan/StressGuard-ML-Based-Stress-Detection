import { expect, test } from "@playwright/test";
import { mkdir } from "node:fs/promises";
import path from "node:path";

const viewports = [
  { name: "desktop-1920x1080", width: 1920, height: 1080 },
  { name: "desktop-1440x900", width: 1440, height: 900 },
  { name: "desktop-1366x768", width: 1366, height: 768 },
  { name: "tablet-820x1180", width: 820, height: 1180 },
  { name: "pixel-7-412x915", width: 412, height: 915 },
];

test.describe("landing visual QA", () => {
  test.skip(!process.env.LANDING_VISUAL_QA, "Run with LANDING_VISUAL_QA=1 to capture QA artifacts.");

  test("captures the requested responsive views", async ({ browser }, testInfo) => {
    test.setTimeout(120_000);
    const screenshotDir = process.env.QA_SCREENSHOT_DIR ?? testInfo.outputPath("screenshots");
    await mkdir(screenshotDir, { recursive: true });

    for (const viewport of viewports) {
      const page = await browser.newPage({ viewport });
      const browserMessages: string[] = [];
      page.on("console", (message) => {
        if (message.type() === "error" || message.type() === "warning") {
          browserMessages.push(message.text());
        }
      });

      await page.goto("/");
      await page.waitForTimeout(viewport.width <= 620 ? 2100 : 3200);
      await page.locator("main section").first().screenshot({
        path: path.join(screenshotDir, `hero-${viewport.name}.png`),
      });
      await page.locator("#how-it-works").scrollIntoViewIfNeeded();
      await expect(page.locator("#how-it-works ol")).toHaveAttribute("data-revealed", "true");
      await page.waitForTimeout(2200);
      await page.locator("#how-it-works").screenshot({
        path: path.join(screenshotDir, `how-${viewport.name}.png`),
      });
      await page.locator("#health-preview").scrollIntoViewIfNeeded();
      await expect(page.locator("#health-preview [data-revealed]")).toHaveAttribute(
        "data-revealed",
        "true",
      );
      await page.waitForTimeout(1500);
      await page.locator("#about").scrollIntoViewIfNeeded();
      await expect(page.locator("#about")).toHaveAttribute("data-revealed", "true");
      await page.waitForTimeout(1200);

      const cardBoxes = await page.locator("#features article").evaluateAll((cards) =>
        cards.map((card) => {
          const box = card.getBoundingClientRect();
          return { width: Math.round(box.width), height: Math.round(box.height) };
        }),
      );
      expect(new Set(cardBoxes.map(({ width }) => width)).size).toBe(1);
      expect(new Set(cardBoxes.map(({ height }) => height)).size).toBe(1);
      expect(browserMessages).toEqual([]);

      await page.evaluate(() => window.scrollTo(0, 0));
      await page.screenshot({
        path: path.join(screenshotDir, `${viewport.name}.png`),
        fullPage: true,
      });
      await page.close();
    }
  });

  test("reveals the hero in sequence without layout shift", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.addInitScript(() => {
      (window as Window & { __heroLayoutShift?: number }).__heroLayoutShift = 0;
      new PerformanceObserver((list) => {
        for (const entry of list.getEntries()) {
          const shift = entry as PerformanceEntry & { hadRecentInput: boolean; value: number };
          if (!shift.hadRecentInput) {
            (window as Window & { __heroLayoutShift?: number }).__heroLayoutShift! += shift.value;
          }
        }
      }).observe({ type: "layout-shift", buffered: true });
    });
    await page.goto("/");

    const reveals = page.locator("[data-hero-reveal]");
    await expect(reveals).toHaveCount(9);
    const delays = await reveals.evaluateAll((items) =>
      Object.fromEntries(
        items.map((item) => [
          item.getAttribute("data-hero-reveal"),
          getComputedStyle(item).animationDelay,
        ]),
      ),
    );
    expect(delays).toEqual({
      eyebrow: "0.1s",
      "title-one": "0.55s",
      "title-two": "1.32s",
      body: "1.65s",
      actions: "1.9s",
      "benefit-1": "2.12s",
      "benefit-2": "2.24s",
      "benefit-3": "2.36s",
      product: "2.05s",
    });

    await page.waitForTimeout(3200);
    for (const reveal of await reveals.all()) {
      await expect(reveal).toHaveCSS("opacity", "1");
    }
    const layoutShift = await page.evaluate(
      () => (window as Window & { __heroLayoutShift?: number }).__heroLayoutShift ?? 0,
    );
    expect(layoutShift).toBeLessThanOrEqual(0.01);
  });

  test("shows the complete hero immediately with reduced motion", async ({ browser }) => {
    const page = await browser.newPage({
      viewport: { width: 1440, height: 900 },
      reducedMotion: "reduce",
    });
    await page.goto("/");

    const reveals = page.locator("[data-hero-reveal]");
    await expect(reveals).toHaveCount(9);
    for (const reveal of await reveals.all()) {
      await expect(reveal).toHaveCSS("animation-name", "none");
      await expect(reveal).toHaveCSS("opacity", "1");
      await expect(reveal).toHaveCSS("transform", "none");
    }
    await page.close();
  });

  test("reveals the journey progressively and completes its connector", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto("/");

    const steps = page.locator("#how-it-works li");
    await expect(steps).toHaveCount(5);
    await expect(steps.nth(0).locator(":scope > div").first()).toHaveCSS("opacity", "0");

    await page.locator("#how-it-works").scrollIntoViewIfNeeded();
    await expect(page.locator("#how-it-works ol")).toHaveAttribute("data-revealed", "true");
    const visualDelays = await steps.evaluateAll((items) =>
      items.map((item) =>
        getComputedStyle(item.querySelector(":scope > div")!).transitionDelay,
      ),
    );
    expect(visualDelays).toEqual(["0s", "0.34s", "0.68s", "1.02s", "1.36s"]);

    await expect.poll(async () =>
      Number.parseFloat(
        await steps.nth(0).locator(":scope > div").first().evaluate((item) =>
          getComputedStyle(item).opacity,
        ),
      ),
    ).toBeGreaterThan(0);

    await page.waitForTimeout(2100);
    for (const step of await steps.all()) {
      await expect(step.locator(":scope > div").first()).toHaveCSS("opacity", "1");
    }

    const connectorTransform = await page.locator("#how-it-works ol").evaluate((track) =>
      getComputedStyle(track, "::after").transform,
    );
    expect(connectorTransform === "none" || connectorTransform.endsWith(", 1, 0, 0)")).toBe(true);
  });

  test("renders the complete journey immediately with reduced motion", async ({ browser }) => {
    const page = await browser.newPage({
      viewport: { width: 1440, height: 900 },
      reducedMotion: "reduce",
    });
    await page.goto("/");

    const steps = page.locator("#how-it-works li");
    for (const step of await steps.all()) {
      await expect(step.locator(":scope > div").first()).toHaveCSS("opacity", "1");
      await expect(step.locator(":scope > div").first()).toHaveCSS("transform", "none");
    }

    const connectorTransform = await page.locator("#how-it-works ol").evaluate((track) =>
      getComputedStyle(track, "::after").transform,
    );
    expect(connectorTransform).toBe("none");
    await page.close();
  });

  test("reveals analytics cards and charts from left to right", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto("/");

    const cards = page.locator("#health-preview article");
    await expect(cards).toHaveCount(4);
    await expect(cards.nth(0)).toHaveCSS("opacity", "0");

    await page.locator("#health-preview").scrollIntoViewIfNeeded();
    await expect(page.locator("#health-preview [data-revealed]")).toHaveAttribute(
      "data-revealed",
      "true",
    );
    const transitionDelays = await cards.evaluateAll((items) =>
      items.map((item) => getComputedStyle(item).transitionDelay),
    );
    expect(transitionDelays).toEqual(["0s, 0s", "0.15s", "0.3s", "0.45s"]);

    await expect.poll(async () =>
      Number.parseFloat(await cards.nth(0).evaluate((item) => getComputedStyle(item).opacity)),
    ).toBeGreaterThan(0);

    await page.waitForTimeout(1300);
    for (const card of await cards.all()) {
      await expect(card).toHaveCSS("opacity", "1");
    }
    await expect(page.locator("#health-preview svg.recharts-surface")).toHaveCount(4);
  });

  test("shows analytics immediately with reduced motion", async ({ browser }) => {
    const page = await browser.newPage({
      viewport: { width: 1440, height: 900 },
      reducedMotion: "reduce",
    });
    await page.goto("/");

    const cards = page.locator("#health-preview article");
    for (const card of await cards.all()) {
      await expect(card).toHaveCSS("opacity", "1");
      await expect(card).toHaveCSS("transform", "none");
    }
    await expect(page.locator("#health-preview svg.recharts-surface")).toHaveCount(4);
    await page.close();
  });

  test("reveals the final split composition and removes its secondary CTA", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto("/");

    const section = page.locator("#about");
    await expect(section.locator("a")).toHaveCount(1);
    await expect(page.getByRole("link", { name: "View Demo" })).toHaveCount(0);
    await expect(section.locator("img").locator("..")).toHaveCSS("opacity", "0");

    await section.scrollIntoViewIfNeeded();
    await expect(section).toHaveAttribute("data-revealed", "true");
    await expect.poll(async () =>
      Number.parseFloat(await section.locator("img").locator("..").evaluate((item) =>
        getComputedStyle(item).opacity,
      )),
    ).toBeGreaterThan(0);

    await page.waitForTimeout(1200);
    await expect(section.locator("img").locator("..")).toHaveCSS("opacity", "1");
    for (const benefit of await section.locator("span:has(small)").all()) {
      await expect(benefit).toHaveCSS("opacity", "1");
    }
  });

  test("shows the final composition immediately with reduced motion", async ({ browser }) => {
    const page = await browser.newPage({
      viewport: { width: 1440, height: 900 },
      reducedMotion: "reduce",
    });
    await page.goto("/");

    const section = page.locator("#about");
    await expect(section.locator("img").locator("..")).toHaveCSS("opacity", "1");
    await expect(section.locator("img").locator("..")).toHaveCSS("transform", "none");
    await page.close();
  });
});

import { expect, test } from "@playwright/test";
import { mkdir } from "node:fs/promises";
import path from "node:path";

const desktopViewports = [
  { name: "desktop-1920x1080", width: 1920, height: 1080 },
  { name: "desktop-1440x900", width: 1440, height: 900 },
  { name: "desktop-1366x768", width: 1366, height: 768 },
];

const modes = [
  { name: "login", path: "/auth", heading: "Welcome back", submit: "Sign in" },
  {
    name: "register",
    path: "/auth?mode=register",
    heading: "Create your account",
    submit: "Create account",
  },
];

test.describe("auth visual QA", () => {
  test.skip(!process.env.AUTH_VISUAL_QA, "Run with AUTH_VISUAL_QA=1 to capture auth artifacts.");

  test("captures login and register in one desktop viewport without overlap", async ({
    browser,
  }, testInfo) => {
    const screenshotDir = process.env.QA_SCREENSHOT_DIR ?? testInfo.outputPath("screenshots");
    await mkdir(screenshotDir, { recursive: true });

    for (const viewport of desktopViewports) {
      for (const mode of modes) {
        const page = await browser.newPage({ viewport });
        const browserMessages: string[] = [];
        page.on("console", (message) => {
          if (message.type() === "error" || message.type() === "warning") {
            browserMessages.push(message.text());
          }
        });

        await page.goto(mode.path);
        await page.waitForLoadState("networkidle");
        await expect(page.getByRole("heading", { name: mode.heading })).toBeVisible();
        await expect(page.getByLabel("Email address")).toBeVisible();
        await expect(page.getByRole("button", { name: mode.submit })).toBeVisible();
        await expect(page.getByRole("button", { name: "Continue with Google" })).toBeVisible();

        const overflow = await page.evaluate(() => ({
          horizontal: document.documentElement.scrollWidth - document.documentElement.clientWidth,
          vertical: document.documentElement.scrollHeight - window.innerHeight,
        }));
        expect(overflow.horizontal).toBeLessThanOrEqual(1);
        expect(overflow.vertical).toBeLessThanOrEqual(1);

        const watch = await page.locator("[data-auth-watch]").boundingBox();
        expect(watch).not.toBeNull();
        for (const card of await page.locator("[data-auth-metric]").all()) {
          const box = await card.boundingBox();
          expect(box).not.toBeNull();
          if (!watch || !box) continue;
          const overlapWidth = Math.max(0, Math.min(watch.x + watch.width, box.x + box.width) - Math.max(watch.x, box.x));
          const overlapHeight = Math.max(0, Math.min(watch.y + watch.height, box.y + box.height) - Math.max(watch.y, box.y));
          const ratio = (overlapWidth * overlapHeight) / (box.width * box.height);
          const cardName = await card.getAttribute("data-auth-metric");
          expect(
            ratio,
            `${cardName} overlaps the watch at ${mode.name} ${viewport.name}: ${JSON.stringify({ watch, box })}`,
          ).toBeLessThanOrEqual(0.08);
        }

        expect(browserMessages).toEqual([]);
        await page.waitForTimeout(500);
        await page.screenshot({
          path: path.join(screenshotDir, `auth-${mode.name}-${viewport.name}.png`),
          fullPage: true,
        });
        await page.close();
      }
    }
  });

  test("keeps the mobile form usable", async ({ browser }, testInfo) => {
    const screenshotDir = process.env.QA_SCREENSHOT_DIR ?? testInfo.outputPath("screenshots");
    await mkdir(screenshotDir, { recursive: true });
    const page = await browser.newPage({ viewport: { width: 412, height: 915 } });
    await page.goto("/auth");
    await page.waitForLoadState("networkidle");
    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
    await page.screenshot({
      path: path.join(screenshotDir, "auth-login-pixel-7-412x915.png"),
      fullPage: true,
    });
    await page.close();
  });
});

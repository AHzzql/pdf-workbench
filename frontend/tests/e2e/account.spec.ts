import { test, expect } from "@playwright/test";
import path from "node:path";
const fixture = path.resolve("../backend/target/verification/api-sample.pdf");
test("游客默认与账户闭环保留当前 PDF 和提取结果", async ({ page }) => {
  await page.goto("/");
  await expect(
    page.getByRole("button", { name: "登录", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".account-dialog")).not.toBeVisible();
  await page.getByLabel("选择 PDF 文件").setInputFiles(fixture);
  await page.getByRole("button", { name: "提取文本", exact: true }).click();
  await expect(page.locator(".extracted-text")).toContainText("Alpha");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.locator(".account-dialog")).toBeVisible();
  await page.screenshot({
    path: "test-results/account-login.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "创建账户", exact: true }).click();
  const email = `browser-${crypto.randomUUID()}@example.com`;
  await page.locator("dialog [name=displayName]").fill("账户甲");
  await page.locator("dialog [name=email]").fill(email);
  await page.locator("dialog [name=password]").fill("example-password-2026");
  await page
    .locator("dialog [name=confirmPassword]")
    .fill("example-password-2026");
  await page.getByRole("button", { name: "创建账户", exact: true }).click();
  await expect(page.locator("dialog [role=status]")).toContainText(
    "账户已创建",
  );
  await page.locator("dialog [name=password]").fill("example-password-2026");
  await page.locator("dialog .account-primary").click();
  await expect(page.locator(".account-dialog")).not.toBeVisible();
  await expect(page.locator(".account-entry")).toContainText("账户甲");
  await expect(page.locator(".extracted-text")).toContainText("Alpha");
  await page.getByRole("button", { name: "账户设置" }).click();
  await page.locator("dialog [name=displayName]").fill("独立昵称");
  await page.locator("dialog .account-primary").click();
  await expect(page.locator(".account-entry")).toContainText("独立昵称");
  await page.screenshot({
    path: "test-results/account-profile.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "关闭账户面板" }).click();
  await page.reload();
  await expect(page.locator(".account-entry")).toContainText("独立昵称");
  await page.getByLabel("选择 PDF 文件").setInputFiles(fixture);
  await page.getByRole("button", { name: "提取文本", exact: true }).click();
  await expect(page.locator(".extracted-text")).toContainText("Alpha");
  await page.getByRole("button", { name: "账户设置" }).click();
  await page.getByRole("button", { name: "账户安全", exact: true }).click();
  await page
    .locator("dialog [name=currentPassword]")
    .fill("example-password-2026");
  await page
    .locator("dialog [name=password]")
    .fill("changed-example-password-2026");
  await page
    .locator("dialog [name=confirmPassword]")
    .fill("changed-example-password-2026");
  await page.locator("dialog .account-primary").click();
  await expect(page.locator("dialog [role=status]")).toContainText(
    "密码已更新",
  );
  await expect(page.locator(".account-entry")).toContainText("登录");
  await expect(page.locator(".extracted-text")).toContainText("Alpha");
  await page.locator("dialog [name=email]").fill(email);
  await page
    .locator("dialog [name=password]")
    .fill("changed-example-password-2026");
  await page.locator("dialog .account-primary").click();
  await expect(page.locator(".account-entry")).toContainText("独立昵称");
  await page.getByRole("button", { name: "账户设置" }).click();
  await page.getByRole("button", { name: "退出登录", exact: true }).click();
  await expect(page.locator(".account-dialog")).not.toBeVisible();
  await expect(page.locator(".extracted-text")).toContainText("Alpha");
});
test("账户服务故障不阻断游客，入口和弹窗适配各宽度", async ({ page }) => {
  await page.route("**/api/v1/account/session", (route) =>
    route.fulfill({
      status: 503,
      contentType: "application/json",
      body: JSON.stringify({ message: "账户连接暂不可用" }),
    }),
  );
  await page.goto("/");
  await page.getByLabel("选择 PDF 文件").setInputFiles(fixture);
  await page.getByRole("button", { name: "提取文本", exact: true }).click();
  await expect(page.locator(".extracted-text")).toContainText("Alpha");
  for (const width of [320, 390, 768, 960, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await expect(page.locator(".account-dialog")).toBeVisible();
    const layout = await page.evaluate(() => ({
      width: innerWidth,
      offset: scrollX,
      containers: [
        ...document.querySelectorAll(
          "html,body,.workbench,.sidebar,.main,.document-bar,.file-summary,.preview-scroll,.workspace",
        ),
      ].map((el) => ({
        tag: el.tagName,
        class: el.className,
        scroll: el.scrollWidth,
        rect: el.getBoundingClientRect().toJSON(),
      })),
      page: document.documentElement.scrollWidth,
      overflow: [...document.querySelectorAll("body *")]
        .filter((el) => el.checkVisibility())
        .filter((el) => {
          const r = el.getBoundingClientRect();
          return r.right > innerWidth + 1 && r.width < innerWidth * 2;
        })
        .map((el) => ({
          tag: el.tagName,
          class: el.className,
          width: el.getBoundingClientRect().width,
          right: el.getBoundingClientRect().right,
        }))
        .slice(0, 20),
    }));
    expect(layout.page, JSON.stringify(layout)).toBeLessThanOrEqual(width);
    for (const key of ["Tab", "Tab", "Shift+Tab"]) {
      await page.keyboard.press(key);
      expect(
        await page
          .locator(".account-dialog")
          .evaluate((el) => el.contains(document.activeElement)),
      ).toBe(true);
    }
    await page.screenshot({
      path: `test-results/account-dialog-${width}.png`,
      fullPage: true,
    });
    await page.keyboard.press("Escape");
    await expect(page.locator(".account-dialog")).not.toBeVisible();
    await expect(
      page.getByRole("button", { name: "登录", exact: true }),
    ).toBeFocused();
  }
});

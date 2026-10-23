import { test, expect } from "@playwright/test";
import path from "node:path";
test("加密 PDF 仅在确认密码后重试预览", async ({ page }) => {
  await page.goto("/#/text");
  await page
    .getByLabel("选择 PDF 文件")
    .setInputFiles(
      path.resolve("../backend/target/verification/password-fix.pdf"),
    );
  await expect(
    page.getByText("此 PDF 需要打开密码，请输入后点击确认密码"),
  ).toBeVisible();
  const input = page.getByLabel("文档打开密码");
  await input.fill("wrong");
  await input.blur();
  await expect(page.getByText("密码不正确，请修改后重新确认")).toBeHidden();
  await page.getByRole("button", { name: "确认密码", exact: true }).click();
  await expect(page.getByText("密码不正确，请修改后重新确认")).toBeVisible();
  await input.fill("correct-password");
  await input.press("Enter");
  await expect(page.locator(".preview-scroll canvas")).toBeVisible();
  await expect(page.getByText("密码不正确，请修改后重新确认")).toBeHidden();
});

test("连续滚动阅读和密码显示切换", async ({ page }) => {
  await page.goto("/#/text");
  await page
    .getByLabel("选择 PDF 文件")
    .setInputFiles(
      path.resolve("../backend/target/verification/continuous.pdf"),
    );
  await expect(page.locator(".paper")).toHaveCount(4);
  await page.locator(".preview-scroll").evaluate((el) => {
    el.scrollTop = el.scrollHeight;
  });
  await expect(page.locator(".preview-controls")).toContainText("4 / 4");
  await expect(page.locator('[data-page="4"] canvas')).toHaveAttribute(
    "width",
    /[1-9][0-9]*/,
  );
  await page.getByRole("button", { name: "上一页" }).click();
  await expect(page.locator(".preview-controls")).toContainText("3 / 4");
  const input = page.getByLabel("文档打开密码");
  await input.fill("draft");
  await page.getByRole("button", { name: "显示密码", exact: true }).click();
  await expect(input).toHaveAttribute("type", "text");
  await page.getByRole("button", { name: "隐藏密码", exact: true }).click();
  await expect(input).toHaveAttribute("type", "password");
});

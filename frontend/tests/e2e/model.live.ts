import { test, expect } from "@playwright/test";
import path from "node:path";
test("本机模型配置通过 Worker 返回实际文档概览", async ({ page }) => {
  await page.goto("/#/text");
  await page
    .getByLabel("选择 PDF 文件")
    .setInputFiles(path.resolve("../docs/part-b/evidence/api-sample.pdf"));
  await page.getByLabel("打开 PDF 智能助手").click();
  await expect(
    page.getByText("AI 模型尚未配置，普通文档工具不受影响。"),
  ).toBeHidden();
  await expect(
    page.locator(".ai-message.assistant .ai-message-actions"),
  ).toHaveCount(1, { timeout: 100000 });
  await expect(page.locator(".ai-message.assistant .ai-answer")).not.toHaveText(
    "",
  );
});

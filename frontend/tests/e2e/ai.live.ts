import { test, expect } from "@playwright/test";
import path from "node:path";

test("三个推荐问题、自然语言阅读、引用、历史与停止重试", async ({ page }) => {
  test.setTimeout(300000);
  let imports = 0;
  const commands: { mode: string; question: string }[] = [];
  page.on("request", (request) => {
    if (request.url().endsWith("/ai/documents") && request.method() === "POST")
      imports++;
    if (request.url().endsWith("/messages") && request.method() === "POST")
      commands.push(request.postDataJSON());
  });
  await page.goto("/#/text");
  const before = await page.locator(".preview-panel").boundingBox();
  await expect(page.getByRole("dialog", { name: "PDF 智能助手" })).toBeHidden();
  await page
    .getByLabel("选择 PDF 文件")
    .setInputFiles(
      path.resolve("../backend/target/verification/api-sample.pdf"),
    );
  expect(imports).toBe(0);
  await page.getByLabel("打开 PDF 智能助手").click();
  const completed = page.locator(".ai-message.assistant .ai-message-actions");
  const lastAnswer = () =>
    page.locator(".ai-message.assistant").last().locator(".ai-answer");
  await expect(completed).toHaveCount(1, { timeout: 90000 });
  await expect(page.locator(".ai-message.user")).toHaveCount(0);
  await expect(page.locator(".ai-tabs")).toHaveCount(0);
  await expect(page.locator(".ai-starters button")).toHaveCount(3);
  const welcomeTop = await page.locator(".ai-welcome-label").boundingBox();
  const bodyTop = await page.locator(".ai-body").boundingBox();
  expect(welcomeTop!.y).toBeGreaterThanOrEqual(bodyTop!.y + 4);
  const suggestionBottom = await page
    .locator(".ai-starters button")
    .last()
    .boundingBox();
  const composerTop = await page.locator(".ai-compose").boundingBox();
  expect(suggestionBottom!.y + suggestionBottom!.height).toBeLessThanOrEqual(
    composerTop!.y,
  );
  const suggestionStyles = await page
    .locator(".ai-starters button")
    .evaluateAll((buttons) =>
      buttons.map((button) => {
        const style = getComputedStyle(button);
        const luminance = (color: string) => {
          const rgb = color
            .match(/\d+(?:\.\d+)?/g)!
            .slice(0, 3)
            .map(Number)
            .map((value) => {
              const normalized = value / 255;
              return normalized <= 0.04045
                ? normalized / 12.92
                : ((normalized + 0.055) / 1.055) ** 2.4;
            });
          return rgb[0]! * 0.2126 + rgb[1]! * 0.7152 + rgb[2]! * 0.0722;
        };
        const text = luminance(style.color),
          background = luminance(style.backgroundColor);
        return {
          font: style.fontSize,
          weight: style.fontWeight,
          line: style.lineHeight,
          height: button.getBoundingClientRect().height,
          contrast:
            (Math.max(text, background) + 0.05) /
            (Math.min(text, background) + 0.05),
        };
      }),
    );
  for (const style of suggestionStyles) {
    expect(style.font).toBe("14px");
    expect(style.weight).toBe("500");
    expect(style.line).toBe("22px");
    expect(style.height).toBeGreaterThanOrEqual(48);
    expect(style.contrast).toBeGreaterThanOrEqual(4.5);
  }
  expect(imports).toBe(1);
  expect(await page.locator(".preview-panel").boundingBox()).toEqual(before);
  for (const width of [960, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    await page.screenshot({
      path: `test-results/ai-welcome-${width}.png`,
      fullPage: true,
    });
    const box = await page.locator(".ai-window").boundingBox();
    expect(box!.x).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width).toBeLessThanOrEqual(width);
    expect(
      await page
        .locator(".ai-window")
        .evaluate((el) => el.scrollWidth <= el.clientWidth),
    ).toBe(true);
  }
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.locator(".ai-starters button").first().click();
  await expect(completed).toHaveCount(2, { timeout: 90000 });
  expect(commands.at(-1)).toMatchObject({
    mode: "AUTO",
    question: "帮我概括这份文档的主要内容",
  });
  await expect(page.locator(".ai-starters button")).toHaveCount(0);
  await page
    .getByLabel("向当前文档提问")
    .fill("表格中 Alpha 的 Value 是多少？请引用原文。");
  await page.getByLabel("发送 AI 请求").click();
  await expect(completed).toHaveCount(3, { timeout: 90000 });
  await expect(lastAnswer()).toContainText("42");
  await lastAnswer().locator(".ai-cite").first().click();
  await expect(page.locator(".ai-evidence")).toContainText("Alpha");
  await expect(page.locator(".highlight").first()).toBeVisible();
  await page.screenshot({
    path: "test-results/ai-reading-1440.png",
    fullPage: true,
  });
  await page.getByLabel("最小化助手").click();
  await expect(page.getByRole("dialog", { name: "PDF 智能助手" })).toBeHidden();
  await page.getByLabel("打开 PDF 智能助手").click();
  await expect(lastAnswer()).toContainText("42");
  expect(imports).toBe(1);
  await page.reload();
  await page.getByLabel("打开 PDF 智能助手").click();
  await expect(page.locator(".ai-context")).toContainText("api-sample.pdf");
  await expect(lastAnswer()).toContainText("42");
  await expect(completed).toHaveCount(3);
  expect(imports).toBe(1);
  await page.getByRole("button", { name: "历史对话", exact: true }).click();
  await page
    .locator(".ai-history-controls")
    .first()
    .getByText("重命名")
    .click();
  const title = `自然语言阅读回归-${Date.now()}`;
  await page.getByLabel("会话名称").fill(title);
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await page.getByLabel("搜索历史对话").fill(title);
  await expect(page.locator(".ai-history-open")).toHaveCount(1);
  await page.locator(".ai-history-open").click();
  await page.getByLabel("向当前文档提问").fill("请分析 PDF 第 3–8 页。");
  await page.getByLabel("发送 AI 请求").click();
  await expect(completed).toHaveCount(4, { timeout: 90000 });
  await expect(lastAnswer()).toContainText(/1 页|1页|一页/);
  await expect(lastAnswer().locator(".ai-cite")).toHaveCount(0);
  await page.getByLabel("向当前文档提问").fill("分析第 1 页的内容并总结重点。");
  await page.getByLabel("发送 AI 请求").click();
  await expect(page.getByLabel("停止生成")).toBeVisible();
  await page.getByLabel("停止生成").click();
  await expect(page.locator(".ai-message-status")).toContainText("已停止生成");
  await page.getByRole("button", { name: "重试", exact: true }).click();
  await expect(completed).toHaveCount(6, { timeout: 90000 });
  await expect(lastAnswer()).not.toBeEmpty();
  await page
    .getByLabel("向当前文档提问")
    .fill("回到全文，请详细总结所有内容。");
  await page.getByLabel("发送 AI 请求").click();
  await expect(completed).toHaveCount(7, { timeout: 90000 });
  await expect(lastAnswer()).toContainText("42");
  expect(commands.filter((c) => c.mode === "WELCOME")).toHaveLength(1);
  expect(commands.slice(1).every((c) => c.mode === "AUTO")).toBe(true);
  expect(imports).toBe(1);
});

test.afterEach(async ({ page, request }) => {
  const id = await page.evaluate(() =>
    localStorage.getItem("pdf-ai-conversation"),
  );
  if (id) {
    const messages = await (
      await request.get(`/api/v1/pdf/ai/conversations/${id}/messages`)
    ).json();
    if (Array.isArray(messages))
      for (const message of messages)
        if (message.status === "RUNNING")
          await request.post(`/api/v1/pdf/ai/runs/${message.id}/cancel`);
    await request.delete(`/api/v1/pdf/ai/conversations/${id}`, {
      data: { deleteDocument: true },
    });
  }
});

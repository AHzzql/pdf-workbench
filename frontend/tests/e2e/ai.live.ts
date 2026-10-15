import { test, expect } from "@playwright/test";
import path from "node:path";

test("当前 PDF 问答、真实引用、隐藏恢复及历史持久化", async ({ page }) => {
  let imports = 0;
  page.on("request", request => { if (request.url().endsWith("/ai/documents") && request.method()==="POST") imports++; });
  await page.goto("/#/text");
  const before = await page.locator(".preview-panel").boundingBox();
  await expect(page.getByRole("dialog",{name:"PDF 智能助手"})).toBeHidden();
  await page.getByLabel("选择 PDF 文件").setInputFiles(path.resolve("../backend/target/verification/api-sample.pdf"));
  await page.getByLabel("打开 PDF 智能助手").click();
  await expect(page.getByRole("dialog",{name:"PDF 智能助手"})).toBeVisible();
  const tabRows = await page.locator(".ai-tabs button").evaluateAll(buttons =>
    buttons.map(button => button.getBoundingClientRect().y),
  );
  expect(tabRows).toHaveLength(4);
  expect(Math.max(...tabRows) - Math.min(...tabRows)).toBeLessThan(0.5);
  expect(imports).toBe(0);
  expect(await page.locator(".preview-panel").boundingBox()).toEqual(before);
  await page.getByLabel("向当前文档提问").fill("表格中 Alpha 的 Value 是多少？请引用原文。 ");
  await page.getByLabel("发送 AI 请求").click();
  await expect(page.locator(".ai-message.assistant .ai-message-actions")).toBeVisible({timeout:90000});
  await expect(page.locator(".ai-answer")).toContainText("42");
  await expect(page.locator(".ai-cite").first()).toBeVisible();
  await page.locator(".ai-cite").first().click();
  await expect(page.locator(".ai-evidence")).toContainText("Alpha");
  await expect(page.locator(".highlight").first()).toBeVisible();
  await page.screenshot({path:"test-results/ai-reading-1440.png",fullPage:true});
  await page.getByLabel("最小化助手").click();
  await expect(page.getByRole("dialog",{name:"PDF 智能助手"})).toBeHidden();
  await page.getByLabel("打开 PDF 智能助手").click();
  await expect(page.locator(".ai-answer")).toContainText("42");
  expect(imports).toBe(1);
  await page.reload();
  await page.getByLabel("打开 PDF 智能助手").click();
  await expect(page.locator(".ai-context")).toContainText("api-sample.pdf");
  await expect(page.locator(".ai-answer")).toContainText("42");
  expect(imports).toBe(1);
  await page.getByRole("button",{name:"历史对话",exact:true}).click();
  await page.locator(".ai-history-controls").first().getByText("重命名").click();
  const historyTitle=`阅读回归记录-${Date.now()}`;
  await page.getByLabel("会话名称").fill(historyTitle);
  await page.getByRole("button",{name:"保存",exact:true}).click();
  await expect(page.locator(".ai-history-open").first()).toContainText("阅读回归记录");
  await page.getByLabel("搜索历史对话").fill(historyTitle);
  await expect(page.locator(".ai-history-open")).toHaveCount(1);
  await page.locator(".ai-history-open").click();
  await page.getByRole("button",{name:"摘要",exact:true}).click();
  await page.getByLabel("发送 AI 请求").click();
  await expect(page.locator(".ai-message.assistant .ai-message-actions")).toHaveCount(2,{timeout:90000});
  await expect(page.locator(".ai-message-status")).toHaveCount(0);
  expect(imports).toBe(1);
  await page.getByRole("button",{name:"章节",exact:true}).click();
  await page.getByLabel("AI 页码范围").fill("1");
  await page.getByLabel("发送 AI 请求").click();
  await expect(page.getByLabel("停止生成")).toBeVisible();
  await page.getByLabel("停止生成").click();
  await expect(page.locator(".ai-message-status")).toContainText("已停止生成");
  await page.getByRole("button",{name:"重试",exact:true}).click();
  await expect(page.locator(".ai-message.assistant .ai-message-actions")).toHaveCount(4,{timeout:90000});
  await expect(page.locator(".ai-message.assistant").last().locator(".ai-answer")).not.toBeEmpty();
  await page.getByRole("button",{name:"问答",exact:true}).click();
  await page.getByLabel("向当前文档提问").fill("你刚才提到的 Alpha 数值再确认一遍？");
  await page.getByLabel("发送 AI 请求").click();
  await expect(page.locator(".ai-message.assistant .ai-message-actions")).toHaveCount(5,{timeout:90000});
  await expect(page.locator(".ai-message.assistant").last().locator(".ai-answer")).toContainText("42");
  for (const width of [960,1920]) {
    await page.setViewportSize({width,height:1000});
    const box=await page.locator(".ai-window").boundingBox();
    expect(box!.x).toBeGreaterThanOrEqual(0);expect(box!.x+box!.width).toBeLessThanOrEqual(width);
    await page.screenshot({path:`test-results/ai-reading-${width}.png`,fullPage:true});
  }
});

test.afterEach(async ({page,request})=>{
  const id=await page.evaluate(()=>localStorage.getItem("pdf-ai-conversation"));
  if(id) {
    const messages=await (await request.get(`/api/v1/pdf/ai/conversations/${id}/messages`)).json();
    if(Array.isArray(messages)) for(const message of messages) if(message.status==="RUNNING") await request.post(`/api/v1/pdf/ai/runs/${message.id}/cancel`);
    await request.delete(`/api/v1/pdf/ai/conversations/${id}`,{data:{deleteDocument:true}});
  }
});

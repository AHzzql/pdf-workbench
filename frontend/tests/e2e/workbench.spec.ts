import { test, expect } from "@playwright/test";
import path from "node:path";
import fs from "node:fs/promises";
import os from "node:os";
import { execFileSync } from "node:child_process";
const fixture = path.resolve("../backend/target/verification/api-sample.pdf");
test("证书签署页面生成可验证的签名", async ({ page }) => {
  const temporary = await fs.mkdtemp(path.join(os.tmpdir(), "pdf-ui-test-"));
  try {
    const certificate = path.join(temporary, "signer.p12");
    const keytool = process.env.JAVA_HOME
      ? path.join(process.env.JAVA_HOME, "bin", "keytool.exe")
      : "keytool";
    execFileSync(
      keytool,
      [
        "-genkeypair",
        "-alias",
        "signer",
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-keystore",
        certificate,
        "-storetype",
        "PKCS12",
        "-storepass",
        "test-password",
        "-keypass",
        "test-password",
        "-dname",
        "CN=Browser Test",
        "-validity",
        "1",
      ],
      { windowsHide: true, stdio: "pipe" },
    );
    await page.goto("/#/signatures");
    await page.getByLabel("选择 PDF 文件").setInputFiles(fixture);
    await page.getByRole("button", { name: "签署", exact: true }).click();
    await page.getByLabel("签名证书（PKCS#12）").setInputFiles(certificate);
    await page.getByLabel("证书密码").fill("test-password");
    await page.getByRole("button", { name: "签署文档", exact: true }).click();
    await expect(page.locator(".download-card")).toContainText("signed.pdf");
    const pending = page.waitForEvent("download");
    await page.locator(".download-card").click();
    const download = await pending;
    const output = path.join(temporary, "signed.pdf");
    await download.saveAs(output);
    await page.getByLabel("选择 PDF 文件").setInputFiles(output);
    await page.getByRole("button", { name: "验证", exact: true }).click();
    await page.getByRole("button", { name: "验证签名" }).click();
    await expect(page.locator(".signature-facts")).toContainText("有效");
  } finally {
    if (
      path.dirname(path.resolve(temporary)) !== path.resolve(os.tmpdir()) ||
      !path.basename(temporary).startsWith("pdf-ui-test-")
    )
      throw new Error("Invalid temporary path");
    await fs.rm(temporary, { recursive: true, force: true });
  }
});
test("上传、文本、搜索、表格、图片、元数据与下载闭环", async ({ page }) => {
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "将 PDF 拖入此处" }),
  ).toBeVisible();
  await page.screenshot({ path: "test-results/01-upload.png", fullPage: true });
  await page.getByLabel("选择 PDF 文件").setInputFiles(fixture);
  await expect(page.locator("canvas")).toBeVisible();
  await page.getByRole("button", { name: "提取文本", exact: true }).click();
  await expect(page.locator(".extracted-text")).toContainText("Alpha");
  await page.screenshot({ path: "test-results/02-text.png", fullPage: true });
  await page.getByRole("button", { name: "字形属性" }).click();
  await expect(page.locator(".table-scroll")).toContainText("Helvetica");
  await page.getByRole("button", { name: "正则搜索" }).click();
  await page.getByLabel("正则表达式").fill("Alpha");
  await page.getByRole("button", { name: "搜索文本" }).click();
  await expect(page.locator(".match-card")).toContainText("Alpha");
  await page.locator(".match-card").click();
  await expect(page.locator(".highlight").first()).toBeVisible();
  await page.getByRole("link", { name: "表格提取" }).click();
  await page.getByRole("button", { name: "解析表格" }).click();
  await expect(page.locator(".table-scroll")).toContainText("42");
  await page.screenshot({ path: "test-results/03-table.png", fullPage: true });
  await page.getByRole("button", { name: "导出 CSV" }).click();
  await expect(page.getByRole("link", { name: /下载处理结果/ })).toBeVisible();
  const download = page.waitForEvent("download");
  await page.getByRole("link", { name: /下载处理结果/ }).click();
  expect((await download).suggestedFilename()).toBe("table.csv");
  await page.getByRole("link", { name: "图片提取" }).click();
  await page.getByRole("button", { name: "提取图片" }).click();
  await expect(page.locator(".download-card")).toContainText("images.zip");
  await page.getByRole("link", { name: "文档信息" }).click();
  await page.getByRole("button", { name: "读取信息" }).click();
  await expect(page.locator(".metadata")).toContainText(["API sample"]);
});
test("加密、水印、验签与移除页面", async ({ page }) => {
  await page.goto("/#/encrypt");
  await page.getByLabel("选择 PDF 文件").setInputFiles(fixture);
  await page.getByLabel("用户密码", { exact: true }).fill("reader");
  await page.getByLabel("所有者密码").fill("owner");
  await page.getByRole("button", { name: "加密文档" }).click();
  await expect(page.locator(".download-card")).toContainText("encrypted.pdf");
  await page.getByRole("link", { name: "文档水印" }).click();
  await page.getByLabel("水印文字").fill("内部资料");
  await page.getByRole("button", { name: "添加水印" }).click();
  await expect(page.locator(".download-card")).toContainText("watermarked.pdf");
  await page.getByRole("link", { name: "数字签名" }).click();
  await page.getByRole("button", { name: "验证签名" }).click();
  await expect(page.locator(".empty-note")).toContainText("没有数字签名");
  await page
    .getByLabel("选择 PDF 文件")
    .setInputFiles(path.resolve("../backend/target/verification/signed.pdf"));
  await page.getByRole("button", { name: "验证签名" }).click();
  await expect(page.locator(".signature-facts")).toContainText("有效");
  await expect(page.locator(".signature-facts")).toContainText("未评估");
  await expect(page.locator("canvas")).toBeVisible();
  await expect(page.getByText("正在加载文档…", { exact: true })).toBeHidden();
  await page.screenshot({
    path: "test-results/04-signature.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "移除", exact: true }).click();
  await page.getByLabel("确认移除副本中的全部签名").check();
  await page.getByRole("button", { name: "移除签名", exact: true }).click();
  await expect(page.locator(".download-card")).toContainText("unsigned.pdf");
});
for (const width of [960, 1440, 1920])
  test(`布局与键盘焦点 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    await page.goto("/");
    await page.keyboard.press("Tab");
    await expect(page.locator(":focus")).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true);
    await page.screenshot({
      path: `test-results/layout-${width}.png`,
      fullPage: true,
    });
  });

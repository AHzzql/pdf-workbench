const { createRequire } = require("node:module");
const { chromium } = createRequire(
  require("node:path").resolve(__dirname, "../../../frontend/package.json"),
)("playwright");
const assert = require("node:assert/strict");
const { writeFileSync, mkdirSync } = require("node:fs");
const { readFile } = require("node:fs/promises");
const http = require("node:http");
const path = require("node:path");
(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({
    viewport: { width: 1440, height: 1040 },
  });
  const output = path.resolve(
    __dirname,
    "../../../frontend/test-results/account-ui",
  );
  mkdirSync(output, { recursive: true });
  const files = new Map([
    ["/", ["index.html", "text/html; charset=utf-8"]],
    ["/prototype.css", ["prototype.css", "text/css; charset=utf-8"]],
    ["/prototype.js", ["prototype.js", "text/javascript; charset=utf-8"]],
  ]);
  const server = http.createServer(async (req, res) => {
    const asset = files.get(new URL(req.url, "http://localhost").pathname);
    if (!asset) {
      res.writeHead(404).end();
      return;
    }
    try {
      const data = await readFile(path.join(__dirname, asset[0]));
      res.writeHead(200, { "Content-Type": asset[1] });
      res.end(data);
    } catch {
      res.writeHead(500).end();
    }
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const baseURL = `http://127.0.0.1:${server.address().port}`;
  const errors = [];
  const requests = [];
  const results = [];
  page.on("pageerror", (e) => errors.push(String(e)));
  page.on("request", (r) => requests.push(r.url()));
  page.on("response", (r) => {
    if (r.status() >= 400) errors.push(`${r.status()} ${r.url()}`);
  });
  const open = async (theme, screen) => {
    await page.goto(`${baseURL}/#${theme}/${screen}`);
    await page.waitForFunction(
      ([theme, screen]) =>
        document.body.dataset.theme === theme &&
        document.body.dataset.screen === screen,
      [theme, screen],
    );
  };
  try {
    for (const width of [320, 390, 768, 960, 1440, 1920]) {
      await page.setViewportSize({ width, height: 1040 });
      for (const theme of ["a", "b", "c"])
        for (const screen of ["login", "register", "profile", "security"]) {
          await open(theme, screen);
          const overflow = await page.evaluate(() => ({
            document: document.documentElement.scrollWidth,
            viewport: innerWidth,
            controls: [...document.querySelectorAll("button,input,select")]
              .filter((el) => el.checkVisibility())
              .filter(
                (el) =>
                  el.getBoundingClientRect().right > innerWidth + 1 ||
                  el.getBoundingClientRect().left < -1,
              )
              .map((el) => el.outerHTML.slice(0, 120)),
          }));
          assert.ok(
            overflow.document <= width,
            `${width}/${theme}/${screen}: document overflow ${JSON.stringify(overflow)}`,
          );
          assert.deepEqual(
            overflow.controls,
            [],
            `${width}/${theme}/${screen}: controls clipped`,
          );
          assert.equal(
            await page.locator(".screen-switch [aria-current=page]").count(),
            1,
          );
          results.push({ width, theme, screen, layout: "passed" });
        }
    }
    await page.setViewportSize({ width: 1440, height: 1040 });
    await open("a", "login");
    await page.getByRole("button", { name: "B 纸感编辑室" }).click();
    await page.waitForFunction(() => document.body.dataset.theme === "b");
    await page.locator(".screen-switch button[data-screen=register]").click();
    await page.waitForFunction(
      () => document.body.dataset.screen === "register",
    );
    await page.locator("#nickname").fill("测试昵称");
    await page.locator("#email").fill("preview@example.com");
    await page.locator("#password").fill("prototype-example-value");
    await page.locator("#confirm-password").fill("different-example-value");
    await page.locator(".auth-submit").click();
    assert.equal(
      await page.locator(".inline-error").innerText(),
      "两次输入的密码不一致。",
    );
    await page.locator("#confirm-password").fill("prototype-example-value");
    const before = requests.length;
    await page.locator(".auth-submit").click();
    assert.ok(
      (await page.locator(".toast").innerText()).includes("原型不创建账户"),
    );
    assert.equal(await page.locator("#password").inputValue(), "");
    assert.equal(requests.length, before, "registration sent a request");
    await open("a", "login");
    await page.locator("#email").fill("invalid-email");
    assert.equal(
      await page.locator("#email").evaluate((e) => e.checkValidity()),
      false,
    );
    await page.locator("#email").fill("preview@example.com");
    await page.locator("#password").fill("prototype-example-value");
    await page.getByRole("button", { name: "显示密码" }).click();
    assert.equal(await page.locator("#password").getAttribute("type"), "text");
    await page.getByRole("button", { name: "隐藏密码" }).click();
    assert.equal(
      await page.locator("#password").getAttribute("type"),
      "password",
    );
    await page.locator(".auth-submit").click();
    assert.ok(
      (await page.locator(".toast").innerText()).includes("原型不验证身份"),
    );
    assert.equal(await page.locator("#password").inputValue(), "");
    await page.selectOption("#state", "loading");
    assert.equal(await page.locator(".auth-submit").isDisabled(), true);
    assert.equal(await page.locator("#email").isDisabled(), true);
    assert.equal(
      await page.locator("#auth-form").getAttribute("aria-busy"),
      "true",
    );
    await page.selectOption("#state", "error");
    assert.equal(await page.locator(".inline-error").isVisible(), true);
    assert.equal(await page.locator(".auth-submit").isDisabled(), false);
    await page.selectOption("#state", "empty");
    assert.equal(await page.locator("#email").inputValue(), "");
    await page.selectOption("#state", "normal");
    await page.locator("#email").focus();
    await page.keyboard.press("Tab");
    assert.equal(
      await page
        .locator("#password")
        .evaluate((el) => el === document.activeElement),
      true,
    );
    await open("c", "profile");
    await page.locator("#profile-name").fill("<img src=x onerror=alert(1)>");
    await page.locator("#profile-form button[type=submit]").click();
    const expected = await page.locator("#profile-name").inputValue();
    assert.equal(await page.locator(".summary-name").innerText(), expected);
    assert.equal(await page.locator(".summary-name img").count(), 0);
    assert.equal(
      await page.locator(".sidebar-user strong").innerText(),
      expected,
    );
    await page.selectOption("#state", "empty");
    assert.equal(await page.locator("#profile-name").inputValue(), "");
    assert.equal(await page.locator(".summary-name").innerText(), "尚未设置");
    await page.selectOption("#state", "loading");
    assert.equal(await page.locator("#profile-form button").isDisabled(), true);
    await open("b", "security");
    await page.locator("[name=currentPassword]").fill("current-example-value");
    await page.locator("[name=newPassword]").fill("new-example-value");
    await page
      .locator("[name=confirmPassword]")
      .fill("different-example-value");
    await page.locator("#security-form button").click();
    assert.equal(
      await page.locator(".security-error").innerText(),
      "两次输入的新密码不一致。",
    );
    await page.locator("[name=newPassword]").fill("current-example-value");
    await page.locator("[name=confirmPassword]").fill("current-example-value");
    await page.locator("#security-form button").click();
    assert.equal(
      await page.locator(".security-error").innerText(),
      "新密码不能与当前密码相同。",
    );
    await page.locator("[name=newPassword]").fill("new-example-value");
    await page.locator("[name=confirmPassword]").fill("new-example-value");
    await page.locator("#security-form button").click();
    assert.ok(
      (await page.locator("#security-form .save-note").innerText()).includes(
        "原型不修改密码",
      ),
    );
    assert.equal(await page.locator("[name=newPassword]").inputValue(), "");
    const storage = await page.evaluate(() => [
      localStorage.length,
      sessionStorage.length,
    ]);
    assert.deepEqual(storage, [0, 0]);
    assert.deepEqual(errors, []);
    for (const theme of ["a", "b", "c"])
      for (const screen of ["login", "profile"]) {
        await open(theme, screen);
        await page.screenshot({
          path: path.join(output, `account-${theme}-${screen}.png`),
          fullPage: true,
        });
      }
    await page.setViewportSize({ width: 390, height: 844 });
    await open("a", "register");
    await page.screenshot({
      path: path.join(output, "account-a-mobile.png"),
      fullPage: true,
    });
    writeFileSync(
      path.join(output, "account-prototype-results.json"),
      JSON.stringify(
        {
          layouts: results,
          interactions: "passed",
          runtimeErrors: errors,
          externalRequests: requests.filter(
            (url) => !url.startsWith(baseURL + "/"),
          ),
          storage,
        },
        null,
        2,
      ),
    );
    console.log(
      `PASS: ${results.length} responsive theme/screen combinations; form validation, navigation, password visibility, UI states, keyboard focus, text escaping, no network submission/storage; 7 screenshots.`,
    );
  } finally {
    await browser.close();
    await new Promise((resolve) => server.close(resolve));
  }
})().catch((e) => {
  console.error(e);
  process.exit(1);
});

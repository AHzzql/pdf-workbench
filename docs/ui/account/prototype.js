const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => [...document.querySelectorAll(selector)];
const themes = new Set(["a", "b", "c"]);
const screens = new Set(["login", "register", "profile", "security"]);
const icons = {
  text: "M6 3h9l4 4v14H6z M14 3v5h5 M9 12h7 M9 16h5",
  tables: "M3 4h18v16H3z M3 9h18 M9 4v16 M3 14h18",
  images: "M3 4h18v16H3z M3 16l5-5 4 4 3-3 6 6 M16 8h.01",
  encrypt: "M5 10h14v11H5z M8 10V6a4 4 0 0 1 8 0v4 M12 14v3",
  signatures: "M14 4a5 5 0 1 1-2 9l-7 7H2v-3l7-7a5 5 0 0 1 5-6z M16 7h.01",
  watermark: "M12 2S5 10 5 15a7 7 0 0 0 14 0c0-5-7-13-7-13z",
  info: "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M12 11v6 M12 7h.01",
  arrow: "M5 12h14 M14 7l5 5-5 5",
  check: "M4 12l5 5L20 6",
  idea: "M9 18h6 M10 21h4 M8 14a6 6 0 1 1 8 0c-1 1-1 2-1 2H9s0-1-1-2",
  user: "M12 12c2.5 0 4.5-2 4.5-4.5S14.5 3 12 3 7.5 5 7.5 7.5 9.5 12 12 12z M5 21c0-3.5 3-6 7-6s7 2.5 7 6",
  mail: "M3 5h18v14H3z M3 5l9 7 9-7",
  eye: "M2 12s3-7 10-7 10 7 10 7-3 7-10 7S2 12 2 12z M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0",
  chevron: "M9 5l7 7-7 7",
  logout: "M9 3H4v18h5 M10 12h12 M17 7l5 5-5 5",
};
$$("[data-icon]").forEach((element) => {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  Object.entries({
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    "stroke-width": "1.6",
    "stroke-linecap": "round",
    "stroke-linejoin": "round",
    "aria-hidden": "true",
  }).forEach(([key, value]) => svg.setAttribute(key, value));
  const path = document.createElementNS(svg.namespaceURI, "path");
  path.setAttribute("d", icons[element.dataset.icon]);
  svg.append(path);
  element.append(svg);
});

let theme = "a";
let screen = "login";
let toastTimer;
function toast(message) {
  clearTimeout(toastTimer);
  $(".toast").textContent = message;
  $(".toast").hidden = false;
  toastTimer = setTimeout(() => {
    $(".toast").hidden = true;
  }, 3500);
}
function error(message) {
  $(".inline-error").textContent = message;
  $(".inline-error").hidden = !message;
}
function updateName(name) {
  $(".summary-name").textContent = name || "尚未设置";
  $(".sidebar-user strong").textContent = name || "个人账户";
  $(".mini-avatar").textContent = name.slice(0, 1) || "人";
  $(".hero-avatar").firstChild.textContent = name.slice(0, 1) || "人";
  $(".account-intro h2").textContent = name ? `你好，${name}。` : "你好。";
}
function applyState() {
  const state = $("#state").value;
  const register = screen === "register";
  document.body.dataset.state = state;
  $$("form button, form input").forEach((element) => {
    element.disabled = state === "loading";
  });
  $$("form").forEach((form) =>
    form.setAttribute("aria-busy", String(state === "loading")),
  );
  error(
    state === "error"
      ? register
        ? "暂时无法创建账户，请稍后重试。"
        : "邮箱或密码不正确，请重新输入。"
      : "",
  );
  $(".submit-label").textContent =
    state === "loading"
      ? register
        ? "正在创建账户…"
        : "正在登录…"
      : register
        ? "创建账户"
        : "登录工作台";
  const account = screen === "profile" || screen === "security";
  $(".account-status").hidden = !account || state === "normal";
  const accountMessages = {
    loading: "正在提交更改…",
    error: "提交失败，请检查输入后重试。",
    empty: "账户资料尚未填写。添加昵称后即可在工作台显示。",
  };
  $(".account-status").textContent = accountMessages[state] || "";
  if (state === "empty") {
    $$("form").forEach((form) => form.reset());
    $("#profile-name").value = "";
    updateName("");
  } else {
    $("#profile-name").value = "林知远";
    updateName("林知远");
  }
  $$(".save-note").forEach((note) => {
    note.textContent = "";
  });
  $(".security-error").hidden = true;
}
function render() {
  const parts = location.hash.slice(1).split("/");
  theme = themes.has(parts[0]) ? parts[0] : "a";
  screen = screens.has(parts[1]) ? parts[1] : "login";
  const account = screen === "profile" || screen === "security";
  const register = screen === "register";
  document.body.dataset.theme = theme;
  document.body.dataset.screen = screen;
  $$(".theme-switch button").forEach((button) =>
    button.setAttribute("aria-pressed", String(button.dataset.theme === theme)),
  );
  $$("[data-screen]:not(body)").forEach((button) => {
    if (button.dataset.screen === screen)
      button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
  });
  $$(".brand").forEach((brand) => {
    brand.href = `#${theme}/login`;
  });
  $(".auth-shell").hidden = account;
  $(".account-shell").hidden = !account;
  $(".profile-section").hidden = screen !== "profile";
  $(".security-section").hidden = screen !== "security";
  $$(".register-only").forEach((element) => {
    element.hidden = !register;
  });
  $("#nickname").required = register;
  $("#confirm-password").required = register;
  $("#auth-eyebrow").textContent = register
    ? "MAKE ROOM FOR IDEAS"
    : "WELCOME BACK";
  $("#auth-title").textContent = register ? "创建你的账户" : "欢迎回来";
  $("#auth-description").textContent = register
    ? "开启专注、高效的文档工作。"
    : "登录账户，继续你的文档工作。";
  $("#password").placeholder = register ? "设置登录密码" : "请输入密码";
  $("#alternative-text").textContent = register ? "已有账户？" : "还没有账户？";
  $("#alternative-action").textContent = register ? "返回登录 ↗" : "创建账户 ↗";
  $("#auth-form").reset();
  $("#security-form").reset();
  $("#password").type = "password";
  $(".reveal").setAttribute("aria-label", "显示密码");
  $(".reveal").setAttribute("aria-pressed", "false");
  $("#state").value = "normal";
  $(".toast").hidden = true;
  applyState();
}
function navigate(nextScreen) {
  location.hash = `${theme}/${nextScreen}`;
}
$$(".theme-switch button").forEach((button) =>
  button.addEventListener("click", () => {
    location.hash = `${button.dataset.theme}/${screen}`;
  }),
);
$$("button[data-screen]").forEach((button) =>
  button.addEventListener("click", () => navigate(button.dataset.screen)),
);
$("#alternative-action").addEventListener("click", () =>
  navigate(screen === "register" ? "login" : "register"),
);
$("#state").addEventListener("change", applyState);
$(".reveal").addEventListener("click", (event) => {
  const reveal = $("#password").type === "password";
  $("#password").type = reveal ? "text" : "password";
  event.currentTarget.setAttribute(
    "aria-label",
    reveal ? "隐藏密码" : "显示密码",
  );
  event.currentTarget.setAttribute("aria-pressed", String(reveal));
});
$("#auth-form").addEventListener("submit", (event) => {
  event.preventDefault();
  if (!$("#auth-form").reportValidity()) return;
  if (screen === "register" && !$("#nickname").value.trim()) {
    error("请输入昵称。");
    $("#nickname").focus();
    return;
  }
  if (
    screen === "register" &&
    $("#password").value !== $("#confirm-password").value
  ) {
    error("两次输入的密码不一致。");
    $("#confirm-password").focus();
    return;
  }
  error("");
  toast(
    screen === "register"
      ? "注册表单校验通过；原型不创建账户。"
      : "登录表单校验通过；原型不验证身份。",
  );
  $$("input[type=password], #password").forEach((input) => {
    input.value = "";
  });
});
$("#profile-form").addEventListener("submit", (event) => {
  event.preventDefault();
  if (!$("#profile-form").reportValidity()) return;
  const name = $("#profile-name").value.trim();
  if (!name) {
    $("#profile-name").setCustomValidity("请输入昵称。");
    $("#profile-name").reportValidity();
    return;
  }
  updateName(name);
  $("#profile-form .save-note").textContent = "已更新预览，未持久化";
});
$("#profile-name").addEventListener("input", () =>
  $("#profile-name").setCustomValidity(""),
);
$("#security-form").addEventListener("submit", (event) => {
  event.preventDefault();
  if (!$("#security-form").reportValidity()) return;
  const form = event.currentTarget;
  const message =
    form.elements.newPassword.value !== form.elements.confirmPassword.value
      ? "两次输入的新密码不一致。"
      : form.elements.currentPassword.value === form.elements.newPassword.value
        ? "新密码不能与当前密码相同。"
        : "";
  $(".security-error").textContent = message;
  $(".security-error").hidden = !message;
  if (message) return;
  $("#security-form .save-note").textContent = "表单校验通过，原型不修改密码";
  form.reset();
});
window.addEventListener("hashchange", render);
render();

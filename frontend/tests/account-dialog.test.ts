import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import AccountDialog from "../src/components/AccountDialog.vue";
import { useAccount } from "../src/composables/useAccount";
import { clearAccountToken } from "../src/api/account";
beforeEach(() => {
  HTMLDialogElement.prototype.showModal = function () {
    this.open = true;
  };
  HTMLDialogElement.prototype.close = function () {
    this.open = false;
  };
  vi.stubGlobal(
    "fetch",
    vi.fn(
      async () =>
        new Response(JSON.stringify({ authenticated: false, user: null }), {
          status: 200,
        }),
    ),
  );
});
afterEach(() => {
  useAccount().user.value = null;
  clearAccountToken();
  vi.unstubAllGlobals();
});
it("默认关闭，可打开并通过继续游客退出", async () => {
  const wrapper = mount(AccountDialog, { props: { open: false } });
  expect(wrapper.find("dialog").element.open).toBe(false);
  await wrapper.setProps({ open: true });
  await flushPromises();
  expect(wrapper.find("dialog").element.open).toBe(true);
  await wrapper.find(".account-dialog-footer button").trigger("click");
  expect(wrapper.emitted("close")).toHaveLength(1);
  await wrapper.setProps({ open: false });
  expect(wrapper.find("dialog").element.open).toBe(false);
  wrapper.unmount();
});
it("确认密码错误不发送注册请求，关闭时清理密码", async () => {
  const wrapper = mount(AccountDialog, { props: { open: false } });
  await wrapper.setProps({ open: true });
  await flushPromises();
  await wrapper.find(".account-alternative button").trigger("click");
  await wrapper.find("[name=displayName]").setValue("测试昵称");
  await wrapper.find("[name=email]").setValue("fixture@example.com");
  await wrapper.find("[name=password]").setValue("example-password");
  await wrapper.find("[name=confirmPassword]").setValue("different-password");
  await wrapper.find("form").trigger("submit");
  expect(wrapper.find("[role=alert]").text()).toContain("不一致");
  expect(
    vi
      .mocked(fetch)
      .mock.calls.filter(([url]) => String(url).endsWith("/register")),
  ).toHaveLength(0);
  await wrapper.setProps({ open: false });
  expect(wrapper.find<HTMLInputElement>("[name=password]").element.value).toBe(
    "",
  );
  wrapper.unmount();
});

import { afterEach, describe, expect, it, vi } from "vitest";
import {
  accountCsrf,
  accountWrite,
  clearAccountToken,
} from "../src/api/account";
import { chatSpace, useAccount } from "../src/composables/useAccount";
import { aiRequest } from "../src/api/ai";
const account = useAccount();
afterEach(() => {
  vi.unstubAllGlobals();
  account.user.value = null;
  clearAccountToken();
});
function response(value: unknown, status = 200) {
  return new Response(status === 204 ? null : JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
describe("可选账户与聊天空间", () => {
  it("账户写请求使用 CSRF，登录后令牌重新获取", async () => {
    const fetch = vi.fn(async (url: string) =>
      url.endsWith("/csrf")
        ? response({ headerName: "X-CSRF-TOKEN", token: "fixture-csrf" })
        : response({
            authenticated: true,
            user: { id: "a", email: "a@example.com", displayName: "A" },
          }),
    );
    vi.stubGlobal("fetch", fetch);
    await account.login("a@example.com", "example-password");
    expect(chatSpace.value).toBe("a");
    expect(fetch.mock.calls[1]![0]).toContain("/login");
    await accountCsrf();
    expect(
      fetch.mock.calls.filter(([url]) => url.endsWith("/csrf")),
    ).toHaveLength(2);
  });
  it("旧会话查询不能覆盖新登录身份", async () => {
    let resolve!: (value: Response) => void;
    vi.stubGlobal(
      "fetch",
      vi.fn((url: string) =>
        url.endsWith("/session")
          ? new Promise<Response>((r) => {
              resolve = r;
            })
          : Promise.resolve(
              url.endsWith("/csrf")
                ? response({ headerName: "X-CSRF-TOKEN", token: "fixture" })
                : response({
                    authenticated: true,
                    user: {
                      id: "new",
                      displayName: "New",
                      email: "new@example.com",
                    },
                  }),
            ),
      ),
    );
    const pending = account.refresh();
    await account.login("new@example.com", "example-password");
    resolve(response({ authenticated: false, user: null }));
    await pending;
    expect(account.user.value?.id).toBe("new");
  });
  it("CSRF 错误不自动重放账户写操作", async () => {
    const fetch = vi.fn(async (url: string) =>
      url.endsWith("/csrf")
        ? response({ headerName: "X-CSRF-TOKEN", token: "fixture" })
        : response(
            { code: "ACCOUNT_CSRF_INVALID", message: "会话校验失败" },
            403,
          ),
    );
    vi.stubGlobal("fetch", fetch);
    await expect(accountWrite("/register", "POST", {})).rejects.toThrow(
      "会话校验失败",
    );
    expect(
      fetch.mock.calls.filter(([url]) => url.endsWith("/register")),
    ).toHaveLength(1);
  });
  it("退出失败不会假报游客身份", async () => {
    account.user.value = { id: "a", email: "a@example.com", displayName: "A" };
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) =>
        url.endsWith("/csrf")
          ? response({ headerName: "X-CSRF-TOKEN", token: "fixture" })
          : response({ message: "暂不可用" }, 503),
      ),
    );
    await expect(account.logout()).rejects.toThrow();
    expect(account.user.value?.id).toBe("a");
  });
  it("账户 AI 请求携带当前空间与 CSRF，拒绝晚到的旧空间响应", async () => {
    account.user.value = { id: "a", email: "a@example.com", displayName: "A" };
    let finish!: (value: Response) => void;
    const fetch = vi.fn((url: string) =>
      url.endsWith("/csrf")
        ? Promise.resolve(
            response({ headerName: "X-CSRF-TOKEN", token: "fixture" }),
          )
        : new Promise<Response>((r) => {
            finish = r;
          }),
    );
    vi.stubGlobal("fetch", fetch);
    const request = aiRequest("/conversations/search", "POST", { query: "" });
    await vi.waitFor(() => expect(finish).toBeDefined());
    account.user.value = { id: "b", email: "b@example.com", displayName: "B" };
    finish(response([{ id: "private-a" }]));
    await expect(request).rejects.toThrow("聊天空间已切换");
  });
  it("账户连接失败不建立虚假登录态", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
    await account.refresh();
    expect(account.user.value).toBeNull();
    expect(account.unavailable.value).toBe(true);
  });
});

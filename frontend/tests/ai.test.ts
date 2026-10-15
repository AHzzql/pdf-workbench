import { afterEach, describe, expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import AiAnswer from "../src/components/AiAnswer.vue";
import AiAssistant from "../src/components/AiAssistant.vue";
import type { AiMessage, Citation } from "../src/types/ai";

afterEach(() => {
  vi.unstubAllGlobals();
  document.body.innerHTML = "";
  localStorage.clear();
});
const citation: Citation = {
  id: "p2-0",
  documentId: "d",
  page: 2,
  quote: "Evidence",
  bounds: [{ x: 10, y: 20, width: 50, height: 10 }],
};
const message: AiMessage = {
  id: "m",
  conversationId: "c",
  role: "assistant",
  text: "## Answer\n**Fact** [p2-0] [p9-0] <img src=x onerror=alert(1)>",
  status: "COMPLETED",
  error: "",
  mode: "ASK",
  question: "question",
  pages: null,
  chapterId: null,
  citations: [citation],
  createdAt: 1,
  sequence: 1,
};
describe("AI 阅读界面", () => {
  it("只有真实引用可点击，模型 HTML 作为文本显示", async () => {
    const wrapper = mount(AiAnswer, { props: { message } });
    expect(wrapper.findAll("button")).toHaveLength(1);
    expect(wrapper.find("img").exists()).toBe(false);
    expect(wrapper.text()).toContain("[p9-0]");
    await wrapper.find("button").trigger("click");
    expect(wrapper.emitted("locate")?.[0]).toEqual([citation]);
    wrapper.unmount();
  });
  it("默认隐藏且不请求 AI，开关浮窗保留输入", async () => {
    const fetch = vi.fn(
      async (url: string) =>
        new Response(
          JSON.stringify(url.endsWith("/status") ? { configured: true } : []),
        ),
    );
    vi.stubGlobal("fetch", fetch);
    const wrapper = mount(AiAssistant, {
      props: { file: new File(["PDF"], "sample.pdf"), password: "" },
      attachTo: document.body,
    });
    expect(fetch).not.toHaveBeenCalled();
    const dialog = document.querySelector<HTMLElement>(".ai-window")!;
    expect(dialog.style.display).toBe("none");
    (document.querySelector(".ai-launcher") as HTMLButtonElement).click();
    await flushPromises();
    expect(dialog.style.display).not.toBe("none");
    expect(document.querySelector(".ai-error")).toBeNull();
    const input = document.querySelector("textarea")!;
    input.value = "持续阅读";
    input.dispatchEvent(new Event("input"));
    (
      document.querySelector('[aria-label="关闭助手"]') as HTMLButtonElement
    ).click();
    await flushPromises();
    (document.querySelector(".ai-launcher") as HTMLButtonElement).click();
    await flushPromises();
    expect(document.querySelector("textarea")?.value).toBe("持续阅读");
    expect(fetch.mock.calls.every((c) => !c[0].endsWith("/documents"))).toBe(
      true,
    );
    wrapper.unmount();
  });
  it("返回全文问答时清除隐藏的章节范围", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(JSON.stringify({ configured: true }))),
    );
    const wrapper = mount(AiAssistant, {
      props: { file: null, password: "" },
      attachTo: document.body,
    });
    const tabs = Array.from(
      document.querySelectorAll<HTMLButtonElement>(".ai-tabs button"),
    );
    tabs[2]!.click();
    await flushPromises();
    const range = document.querySelector<HTMLInputElement>(
      '[aria-label="AI 页码范围"]',
    )!;
    range.value = "2-4";
    range.dispatchEvent(new Event("input"));
    tabs[0]!.click();
    await flushPromises();
    tabs[2]!.click();
    await flushPromises();
    expect(
      document.querySelector<HTMLInputElement>('[aria-label="AI 页码范围"]')!
        .value,
    ).toBe("");
    wrapper.unmount();
  });
  it("加密历史的密码输入不会在首字符后消失", async () => {
    localStorage.setItem("pdf-ai-conversation", "c");
    const conversation = {
      id: "c",
      documentId: "d",
      title: "Encrypted",
      createdAt: 1,
      updatedAt: 1,
    };
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) => {
        if (url.endsWith("/file")) return new Response("PDF");
        const value = url.endsWith("/status")
          ? { configured: true }
          : url.endsWith("/search")
            ? [conversation]
            : url.endsWith("/documents/d")
              ? { id: "d", name: "encrypted.pdf", pages: 1, encrypted: true }
              : [];
        return new Response(JSON.stringify(value));
      }),
    );
    const wrapper = mount(AiAssistant, {
      props: { file: null, password: "" },
      attachTo: document.body,
    });
    (document.querySelector(".ai-launcher") as HTMLButtonElement).click();
    await flushPromises();
    const input =
      document.querySelector<HTMLInputElement>(".ai-password input")!;
    expect(input).not.toBeNull();
    input.value = "r";
    input.dispatchEvent(new Event("input"));
    await wrapper.setProps({ password: "r" });
    expect(document.querySelector(".ai-password input")).not.toBeNull();
    input.value = "reader";
    input.dispatchEvent(new Event("input"));
    await flushPromises();
    expect(wrapper.emitted("password")?.at(-1)).toEqual(["reader"]);
    expect(document.querySelector(".ai-error")).toBeNull();
    wrapper.unmount();
  });
});

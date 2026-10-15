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
  it("默认隐藏且不请求 AI；打开后只生成一次概览并显示三个推荐问题", async () => {
    const fetch = mockReadingApi();
    const wrapper = mount(AiAssistant, {
      props: { file: new File(["PDF"], "sample.pdf"), password: "" },
      attachTo: document.body,
    });
    expect(fetch).not.toHaveBeenCalled();
    expect(
      document.querySelector<HTMLElement>(".ai-window")!.style.display,
    ).toBe("none");
    click(".ai-launcher");
    await flushPromises();
    expect(document.querySelector(".ai-tabs")).toBeNull();
    expect(document.querySelectorAll(".ai-starters button")).toHaveLength(3);
    expect(document.querySelector(".ai-answer")?.textContent).toContain(
      "文档概览",
    );
    const input = document.querySelector("textarea")!;
    input.value = "持续阅读";
    input.dispatchEvent(new Event("input"));
    click('[aria-label="关闭助手"]');
    await flushPromises();
    click(".ai-launcher");
    await flushPromises();
    expect(document.querySelector("textarea")?.value).toBe("持续阅读");
    expect(
      fetch.mock.calls.filter(([url]) => url.endsWith("/documents")),
    ).toHaveLength(1);
    expect(
      fetch.mock.calls.filter(
        ([url, init]) => url.endsWith("/messages") && init?.method === "POST",
      ),
    ).toHaveLength(1);
    wrapper.unmount();
  });
  it("推荐问题使用 AUTO 对话入口，不附带隐藏范围", async () => {
    const fetch = mockReadingApi();
    const wrapper = mount(AiAssistant, {
      props: { file: new File(["PDF"], "sample.pdf"), password: "" },
      attachTo: document.body,
    });
    click(".ai-launcher");
    await flushPromises();
    click(".ai-starters button");
    await flushPromises();
    const request = fetch.mock.calls
      .filter(
        ([url, init]) => url.endsWith("/messages") && init?.method === "POST",
      )
      .at(-1)!;
    expect(JSON.parse(request[1]!.body as string)).toMatchObject({
      mode: "AUTO",
      question: "帮我概括这份文档的主要内容",
      pages: null,
      chapterId: null,
    });
    expect(document.querySelectorAll(".ai-starters button")).toHaveLength(0);
    wrapper.unmount();
  });
  it("中文输入法确认不会误发送，普通 Enter 发送自然语言指令", async () => {
    const fetch = mockReadingApi();
    const wrapper = mount(AiAssistant, {
      props: { file: new File(["PDF"], "sample.pdf"), password: "" },
      attachTo: document.body,
    });
    click(".ai-launcher");
    await flushPromises();
    const input = document.querySelector("textarea")!;
    input.value = "分析第 3–8 页";
    input.dispatchEvent(new Event("input"));
    await flushPromises();
    input.dispatchEvent(
      new KeyboardEvent("keydown", {
        key: "Enter",
        isComposing: true,
        bubbles: true,
      }),
    );
    await flushPromises();
    expect(
      fetch.mock.calls.filter(
        ([, init]) => init?.body && String(init.body).includes('"AUTO"'),
      ),
    ).toHaveLength(0);
    input.dispatchEvent(
      new KeyboardEvent("keydown", { key: "Enter", bubbles: true }),
    );
    await flushPromises();
    expect(
      fetch.mock.calls.filter(
        ([, init]) => init?.body && String(init.body).includes('"AUTO"'),
      ),
    ).toHaveLength(1);
    wrapper.unmount();
  });
  it("目录导航不切换阅读模式，返回后保留草稿", async () => {
    mockReadingApi();
    const wrapper = mount(AiAssistant, {
      props: { file: new File(["PDF"], "sample.pdf"), password: "" },
      attachTo: document.body,
    });
    click(".ai-launcher");
    await flushPromises();
    const input = document.querySelector("textarea")!;
    input.value = "详细总结全文";
    input.dispatchEvent(new Event("input"));
    click(".ai-outline-toggle");
    await flushPromises();
    expect(document.querySelector(".ai-section-title")?.textContent).toContain(
      "文档目录",
    );
    click(".ai-section-title button");
    await flushPromises();
    expect(document.querySelector("textarea")?.value).toBe("详细总结全文");
    expect(document.querySelectorAll(".ai-starters button")).toHaveLength(3);
    wrapper.unmount();
  });
  it("没有 PDF 时保留三个禁用建议，不导入或生成概览", async () => {
    const fetch = mockReadingApi();
    const wrapper = mount(AiAssistant, {
      props: { file: null, password: "" },
      attachTo: document.body,
    });
    click(".ai-launcher");
    await flushPromises();
    const buttons = Array.from(
      document.querySelectorAll<HTMLButtonElement>(".ai-starters button"),
    );
    expect(buttons).toHaveLength(3);
    expect(buttons.every((b) => b.disabled)).toBe(true);
    expect(fetch.mock.calls.some(([url]) => url.endsWith("/documents"))).toBe(
      false,
    );
    wrapper.unmount();
  });
  it("切换 PDF 后忽略旧导入结果，不为旧文件建立会话", async () => {
    const baseFetch = mockReadingApi();
    let resolveOld!: (response: Response) => void;
    const oldResponse = new Promise<Response>((resolve) => {
      resolveOld = resolve;
    });
    const fetch = vi.fn((url: string, init?: RequestInit) => {
      const file =
        init?.body instanceof FormData ? (init.body.get("file") as File) : null;
      if (url.endsWith("/documents") && file?.name === "old.pdf")
        return oldResponse;
      return baseFetch(url, init);
    });
    vi.stubGlobal("fetch", fetch);
    const wrapper = mount(AiAssistant, {
      props: { file: new File(["PDF"], "old.pdf"), password: "" },
      attachTo: document.body,
    });
    click(".ai-launcher");
    await flushPromises();
    await wrapper.setProps({ file: new File(["PDF"], "new.pdf") });
    await flushPromises();
    resolveOld(
      new Response(JSON.stringify({ id: "old", name: "old.pdf", pages: 1 })),
    );
    await flushPromises();
    expect(document.querySelector(".ai-context")?.textContent).toContain(
      "new.pdf",
    );
    expect(
      fetch.mock.calls.filter(([url]) => url.endsWith("/conversations")),
    ).toHaveLength(1);
    expect(
      fetch.mock.calls.some(([url]) => url.includes("/documents/old/")),
    ).toBe(false);
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

function click(selector: string) {
  document.querySelector<HTMLButtonElement>(selector)!.click();
}
function mockReadingApi() {
  const messages: AiMessage[] = [];
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    let value: unknown = [];
    if (url.endsWith("/status")) value = { configured: true };
    else if (url.endsWith("/documents"))
      value = { id: "d", name: "sample.pdf", pages: 8, encrypted: false };
    else if (url.endsWith("/conversations"))
      value = { id: "c", documentId: "d", title: "新对话", updatedAt: 1 };
    else if (url.endsWith("/messages")) {
      if (init?.method === "POST") {
        const body = JSON.parse(init.body as string);
        if (body.mode !== "WELCOME")
          messages.push({
            ...message,
            id: "user",
            role: "user",
            text: body.question,
            mode: body.mode,
          });
        const answer = {
          ...message,
          id: body.requestId,
          mode: body.mode,
          question: body.question,
          text: body.mode === "WELCOME" ? "文档概览 [p2-0]" : "回答 [p2-0]",
        };
        messages.push(answer);
        value = answer;
      } else value = messages;
    }
    return new Response(JSON.stringify(value));
  });
  vi.stubGlobal("fetch", fetch);
  vi.stubGlobal(
    "EventSource",
    class {
      addEventListener() {}
      close() {}
    },
  );
  return fetch;
}

import { describe, it, expect, vi, afterEach } from "vitest";
import { processPdf } from "../src/api/pdf";
afterEach(() => vi.unstubAllGlobals());
describe("PDF HTTP 客户端", () => {
  it("敏感字段只通过 multipart 正文提交", async () => {
    const fetch = vi.fn().mockResolvedValue({
      ok: true,
      headers: new Headers({ "content-type": "application/json" }),
      json: async () => ({ pages: [] }),
    });
    vi.stubGlobal("fetch", fetch);
    await processPdf(
      "text",
      new File(["x"], "a.pdf"),
      { password: "secret", pages: "1", certificatePassword: "" },
      new AbortController().signal,
    );
    expect(fetch.mock.calls[0]?.[0]).toBe("/api/v1/pdf/text");
    const body = fetch.mock.calls[0]?.[1].body as FormData;
    expect(body.get("password")).toBe("secret");
    expect(body.has("certificatePassword")).toBe(true);
  });
  it("错误响应不被作为下载文件", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 403,
        json: async () => ({ message: "文档不允许提取内容" }),
      }),
    );
    await expect(
      processPdf(
        "text",
        new File(["x"], "a.pdf"),
        {},
        new AbortController().signal,
      ),
    ).rejects.toThrow("文档不允许提取内容");
  });
  it("部分图片转换失败时保留 ZIP 清单提示", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue({
          ok: true,
          headers: new Headers({
            "content-type": "application/zip",
            "X-Images-Total": "2",
            "X-Images-Exported": "1",
          }),
          blob: async () => new Blob(["zip"]),
        }),
    );
    const result = await processPdf(
      "images",
      new File(["pdf"], "a.pdf"),
      {},
      new AbortController().signal,
    );
    expect(result.download?.note).toContain("成功转换 1 个");
    expect(result.download?.note).toContain("仅保留原始流");
  });
});

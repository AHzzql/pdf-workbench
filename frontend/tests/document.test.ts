import { describe, it, expect, vi, afterEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { defineComponent } from "vue";
import { useDocument } from "../src/composables/useDocument";
import { processPdf } from "../src/api/pdf";
vi.mock("../src/api/pdf", () => ({ processPdf: vi.fn() }));
const mock = vi.mocked(processPdf);
function setup() {
  let state: ReturnType<typeof useDocument>;
  const wrapper = mount(
    defineComponent({
      setup() {
        state = useDocument();
        return () => null;
      },
    }),
  );
  return { state: state!, wrapper };
}
afterEach(() => vi.restoreAllMocks());
describe("文档任务状态", () => {
  it("拒绝非 PDF 和超限文件", () => {
    const { state, wrapper } = setup();
    state.select(new File(["x"], "x.txt"));
    expect(state.file.value).toBeNull();
    expect(state.error.value).toContain("PDF");
    wrapper.unmount();
  });
  it("文件替换后不接受旧响应", async () => {
    const { state, wrapper } = setup();
    let finish!: (value: Awaited<ReturnType<typeof processPdf>>) => void;
    mock.mockReturnValueOnce(new Promise((resolve) => (finish = resolve)));
    state.select(new File(["a"], "a.pdf"));
    const pending = state.execute("text");
    state.select(new File(["b"], "b.pdf"));
    finish({ data: { pages: ["old"] }, download: null });
    await pending;
    expect(state.result.value).toBeNull();
    expect(state.file.value?.name).toBe("b.pdf");
    expect(state.busy.value).toBe(false);
    wrapper.unmount();
  });
  it("阻止重复提交并保留真实错误", async () => {
    const { state, wrapper } = setup();
    mock.mockRejectedValueOnce(new Error("密码错误"));
    state.select(new File(["a"], "a.pdf"));
    const first = state.execute("text");
    await state.execute("text");
    await first;
    expect(state.error.value).toBe("密码错误");
    expect(state.completed.value).toBe(false);
    expect(state.busy.value).toBe(false);
    wrapper.unmount();
  });
  it("替换和销毁释放下载地址", async () => {
    const create = vi.fn(() => "blob:test"),
      revoke = vi.fn();
    vi.stubGlobal(
      "URL",
      Object.assign(URL, { createObjectURL: create, revokeObjectURL: revoke }),
    );
    const { state, wrapper } = setup();
    mock.mockResolvedValueOnce({
      data: null,
      download: { blob: new Blob(["test"]), name: "out.pdf" },
    });
    state.select(new File(["a"], "a.pdf"));
    await state.execute("encrypt");
    await flushPromises();
    expect(state.download.value?.name).toBe("out.pdf");
    wrapper.unmount();
    expect(revoke).toHaveBeenCalledWith("blob:test");
  });
});

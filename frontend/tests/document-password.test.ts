import { mount } from "@vue/test-utils";
import { describe, it, expect } from "vitest";
import DocumentPassword from "../src/components/DocumentPassword.vue";
describe("文档密码确认", () => {
  it("输入和失焦不验证，点击确认后仅提交完整密码", async () => {
    const w = mount(DocumentPassword, {
      props: { modelValue: "", document: new File(["pdf"], "a.pdf") },
    });
    await w.get("input").setValue("part");
    await w.get("input").trigger("blur");
    expect(w.emitted("update:modelValue")).toBeUndefined();
    await w.get("input").setValue("complete-password");
    await w.get("button").trigger("click");
    expect(w.emitted("update:modelValue")).toEqual([["complete-password"]]);
  });
  it("回车确认，切换文档清除草稿", async () => {
    const w = mount(DocumentPassword, {
      props: { modelValue: "", document: new File(["a"], "a.pdf") },
    });
    await w.get("input").setValue("secret");
    await w.get("input").trigger("keydown", { key: "Enter" });
    expect(w.emitted("update:modelValue")).toEqual([["secret"]]);
    await w.setProps({ document: new File(["b"], "b.pdf") });
    expect((w.get("input").element as HTMLInputElement).value).toBe("");
  });
  it("已确认值保持不变直到重新确认，包括清空密码", async () => {
    const w = mount(DocumentPassword, {
      props: { modelValue: "old", document: null },
    });
    await w.get("input").setValue("");
    expect(w.emitted("update:modelValue")).toBeUndefined();
    await w.get("button").trigger("click");
    expect(w.emitted("update:modelValue")).toEqual([[""]]);
  });
});

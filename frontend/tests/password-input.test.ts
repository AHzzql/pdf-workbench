import { mount } from "@vue/test-utils";
import { it, expect } from "vitest";
import PasswordInput from "../src/components/PasswordInput.vue";
it("显示与隐藏保留密码且不触发值确认", async () => {
  const w = mount(PasswordInput, { props: { modelValue: "secret" } });
  expect(w.get("input").attributes("type")).toBe("password");
  await w.get("button").trigger("click");
  expect(w.get("input").attributes("type")).toBe("text");
  expect((w.get("input").element as HTMLInputElement).value).toBe("secret");
  expect(w.emitted("update:modelValue")).toBeUndefined();
  await w.get("button").trigger("click");
  expect(w.get("input").attributes("type")).toBe("password");
});

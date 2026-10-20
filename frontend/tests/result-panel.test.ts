import { describe, it, expect } from "vitest";
import { mount } from "@vue/test-utils";
import ResultPanel from "../src/components/ResultPanel.vue";

const common = { mode: "extract", completed: true, busy: false };
describe("核心 PDF 结果", () => {
  it("按文本块展示尺寸和样式并定位原页", async () => {
    const bounds = { x: 60, y: 700, width: 72, height: 12 };
    const wrapper = mount(ResultPanel, {
      props: {
        ...common,
        tool: "text",
        result: {
          pages: [
            {
              page: 1,
              text: "Alpha report",
              spans: [],
              paragraphs: [],
              diagnostics: [],
              blocks: [
                {
                  page: 1,
                  text: "Alpha report",
                  start: 0,
                  end: 12,
                  bounds,
                  font: "Helvetica",
                  fontSize: 12,
                  fillColor: {
                    rgb: "#FF0000",
                    colorSpace: "DeviceRGB",
                    components: [1, 0, 0],
                  },
                },
              ],
            },
          ],
        },
      },
    });
    await wrapper.get(".tabs button:last-child").trigger("click");
    expect(wrapper.text()).toContain("文本块属性");
    expect(wrapper.text()).toContain("1 个文本块");
    expect(wrapper.text()).toContain("72.0 × 12.0");
    await wrapper.get(".text-button").trigger("click");
    expect(wrapper.emitted("locate")?.[0]).toEqual([
      { page: 1, bounds: [bounds] },
    ]);
  });

  it("无法验签和未知修订不显示为无效或未修改", () => {
    const wrapper = mount(ResultPanel, {
      props: {
        ...common,
        tool: "signatures",
        result: [
          {
            name: null,
            signer: null,
            algorithm: null,
            integrity: "UNKNOWN",
            hasLaterRevision: null,
            certificateValidity: "UNKNOWN",
            trustStatus: "NOT_EVALUATED",
            revocationChecked: false,
            certificate: null,
            reason: "无法确定签署证书",
          },
        ],
      },
    });
    expect(wrapper.text()).toContain("无法验证");
    expect(wrapper.text()).toContain("无法判定后续修订");
    expect(wrapper.text()).not.toContain("未发现后续修订");
  });

  it("证书摘要与算法可查看", () => {
    const wrapper = mount(ResultPanel, {
      props: {
        ...common,
        tool: "signatures",
        result: [
          {
            name: "signer",
            signer: "CN=Signer",
            algorithm: "SHA256withRSA",
            integrity: "VALID",
            hasLaterRevision: false,
            certificateValidity: "VALID",
            trustStatus: "NOT_EVALUATED",
            revocationChecked: false,
            reason: null,
            certificate: {
              subject: "CN=Signer",
              issuer: "CN=Issuer",
              serialNumber: "12345",
              notBefore: "2026-01-01T00:00:00Z",
              notAfter: "2027-01-01T00:00:00Z",
            },
          },
        ],
      },
    });
    expect(wrapper.text()).toContain("SHA256withRSA");
    expect(wrapper.text()).toContain("CN=Issuer");
    expect(wrapper.text()).toContain("12345");
  });

  it("显示后端总页数并禁用缺少位置的批注定位", async () => {
    const wrapper = mount(ResultPanel, {
      props: {
        ...common,
        tool: "info",
        result: {
          pageCount: 20,
          standardMetadata: {},
          customMetadata: {},
          bookmarks: [],
          diagnostics: [],
          xmp: null,
          annotations: [
            {
              page: 1,
              type: "Text",
              contents: "无定位信息",
              author: null,
              bounds: null,
            },
          ],
        },
      },
    });
    expect(wrapper.text()).toContain("总页数 20");
    expect(wrapper.get(".match-card").attributes("disabled")).toBeDefined();
    await wrapper.get(".match-card").trigger("click");
    expect(wrapper.emitted("locate")).toBeUndefined();
  });
});

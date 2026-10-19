<script setup lang="ts">
import { computed, reactive, ref, watch, onMounted } from "vue";
import { useRoute } from "vue-router";
import AccountDialog from "../components/AccountDialog.vue";
import { useAccount } from "../composables/useAccount";
import AppIcon from "../components/AppIcon.vue";
import PdfPreview from "../components/PdfPreview.vue";
import ResultPanel from "../components/ResultPanel.vue";
import FilePicker from "../components/FilePicker.vue";
import AiAssistant from "../components/AiAssistant.vue";
import { useDocument } from "../composables/useDocument";
import type { Selection } from "../types/pdf";
import type { Field } from "../api/pdf";
const account = useAccount();
const accountOpen = ref(false);
onMounted(() => {
  void account.refresh();
});
const route = useRoute(),
  tool = computed(() => String(route.params.tool || "text"));
const tools = [
  {
    id: "text",
    title: "文本提取",
    hint: "提取文字、样式与位置",
    action: "提取文本",
  },
  {
    id: "tables",
    title: "表格提取",
    hint: "识别规则网格并导出数据",
    action: "解析表格",
  },
  {
    id: "images",
    title: "图片提取",
    hint: "导出图片及原始资源",
    action: "提取图片",
  },
  {
    id: "encrypt",
    title: "文档加密",
    hint: "密码与访问权限",
    action: "加密文档",
  },
  {
    id: "signatures",
    title: "数字签名",
    hint: "签署、验证与管理签名",
    action: "验证签名",
  },
  {
    id: "watermark",
    title: "文档水印",
    hint: "文字与图片标记",
    action: "添加水印",
  },
  {
    id: "info",
    title: "文档信息",
    hint: "元数据、书签与批注",
    action: "读取信息",
  },
];
const active = computed(() => tools.find((item) => item.id === tool.value)!);
const {
  file,
  password,
  busy,
  error,
  result,
  completed,
  download,
  select,
  clear,
  execute,
} = useDocument();
const input = ref<HTMLInputElement | null>(null),
  dragging = ref(false),
  selection = ref<Selection | null>(null),
  mode = ref("extract");
const form = reactive({
  pages: "",
  preserveParagraphs: true,
  pattern: "",
  flags: 0,
  format: "PNG",
  userPassword: "",
  ownerPassword: "",
  keyLength: 256,
  allowPrint: true,
  allowCopy: true,
  allowModify: true,
  certificatePassword: "",
  alias: "",
  reason: "",
  location: "",
  confirmRemoval: false,
  type: "TEXT",
  text: "",
  x: 60,
  y: 60,
  fontSize: 28,
  color: "#334155",
  width: 160,
  height: 100,
  angle: 30,
  opacity: 0.3,
});
const certificate = ref<File | null>(null),
  trust = ref<File | null>(null),
  watermark = ref<File | null>(null);
watch(tool, () => {
  clear();
  selection.value = null;
  mode.value = tool.value === "signatures" ? "verify" : "extract";
  form.certificatePassword = "";
  form.ownerPassword = "";
  form.userPassword = "";
  certificate.value = null;
  trust.value = null;
  form.confirmRemoval = false;
});
watch(mode, () => {
  clear();
  selection.value = null;
});
if (tool.value === "signatures") mode.value = "verify";
function selected(value: File) {
  select(value);
  selection.value = null;
  certificate.value = null;
  trust.value = null;
  form.certificatePassword = "";
  form.userPassword = "";
  form.ownerPassword = "";
}
function choose(event: Event) {
  const target = event.target as HTMLInputElement;
  if (target.files?.[0]) selected(target.files[0]);
  target.value = "";
}
function drop(event: DragEvent) {
  dragging.value = false;
  if (event.dataTransfer?.files[0]) selected(event.dataTransfer.files[0]);
}
const action = computed(() =>
  tool.value === "text" && mode.value === "search"
    ? "搜索文本"
    : tool.value === "signatures"
      ? { verify: "验证签名", sign: "签署文档", remove: "移除签名" }[
          mode.value
        ] || "验证签名"
      : active.value.action,
);
async function submit() {
  const fields: Record<string, Field> = { pages: form.pages };
  let endpoint = tool.value;
  if (tool.value === "text") {
    if (mode.value === "search") {
      endpoint = "text/search";
      fields.pattern = form.pattern;
      fields.flags = form.flags;
    } else fields.preserveParagraphs = form.preserveParagraphs;
  }
  if (tool.value === "images") fields.format = form.format;
  if (tool.value === "encrypt")
    Object.assign(fields, {
      userPassword: form.userPassword,
      ownerPassword: form.ownerPassword,
      keyLength: form.keyLength,
      allowPrint: form.allowPrint,
      allowCopy: form.allowCopy,
      allowModify: form.allowModify,
    });
  if (tool.value === "signatures") {
    endpoint = `signatures/${mode.value}`;
    Object.assign(fields, {
      certificate: certificate.value,
      certificatePassword: form.certificatePassword,
      alias: form.alias,
      reason: form.reason,
      location: form.location,
      trustCertificates: trust.value,
      confirmRemoval: form.confirmRemoval,
    });
  }
  if (tool.value === "watermark")
    Object.assign(fields, {
      type: form.type,
      text: form.text,
      image: watermark.value,
      x: form.x,
      y: form.y,
      fontSize: form.fontSize,
      rgb: parseInt(form.color.slice(1), 16),
      width: form.width,
      height: form.height,
      angle: form.angle,
      opacity: form.opacity,
    });
  await execute(endpoint, fields);
}
function csv(index: number) {
  void execute("tables/csv", { pages: form.pages, tableIndex: index });
}
</script>
<template>
  <div class="workbench">
    <aside class="sidebar">
      <a class="brand" href="#/text"
        ><span class="brand-mark"><AppIcon name="text" /></span
        ><span>文档工作台<small>PDF WORKBENCH</small></span></a
      >
      <div class="nav-caption">文档工具</div>
      <nav aria-label="文档工具">
        <RouterLink
          v-for="item in tools"
          :key="item.id"
          :to="'/' + item.id"
          :class="{ selected: tool === item.id }"
          ><AppIcon :name="item.id" /><span>{{ item.title }}</span
          ><span v-if="tool === item.id" class="nav-indicator"></span
        ></RouterLink>
      </nav>
      <div class="sidebar-footer">
        <span class="local-dot"></span>本地文档处理<span class="version"
          >v1.1</span
        >
      </div>
    </aside>
    <main class="main">
      <header class="topbar">
        <div>
          <div class="eyebrow">文档处理 / {{ active.title }}</div>
          <h1>{{ active.title }}</h1>
        </div>
        <div class="topbar-right">
          <span class="tool-description">{{ active.hint }}</span
          ><a
            href="/swagger-ui/index.html"
            target="_blank"
            rel="noopener"
            class="api-link"
            >API 文档 ↗</a
          ><button
            type="button"
            class="account-entry"
            :aria-label="account.user.value ? '账户设置' : '登录'"
            @click="accountOpen = true"
          >
            <AppIcon name="user" /><span>{{
              account.user.value?.displayName || "登录"
            }}</span>
          </button>
        </div>
      </header>
      <div class="document-bar">
        <div class="file-summary">
          <span class="file-badge">PDF</span>
          <div>
            <strong>{{ file?.name || "尚未选择文档" }}</strong
            ><small>{{
              file
                ? (file.size / 1024 / 1024).toFixed(2) + " MiB"
                : "支持 PDF 文件，最大 50 MiB"
            }}</small>
          </div>
        </div>
        <button class="secondary compact" @click="input?.click()">
          <AppIcon name="upload" />{{ file ? "更换文件" : "选择文件" }}</button
        ><input
          ref="input"
          class="visually-hidden"
          type="file"
          accept=".pdf,application/pdf"
          aria-label="选择 PDF 文件"
          @change="choose"
        />
      </div>
      <div class="workspace">
        <section
          class="preview-panel"
          :class="{ dragging }"
          aria-label="文档预览区"
          @dragover.prevent="dragging = true"
          @dragleave.prevent="dragging = false"
          @drop.prevent="drop"
        >
          <PdfPreview
            v-if="file"
            :file="file"
            :password="password"
            :selection="selection"
          />
          <template v-else
            ><div class="preview-toolbar">
              <span class="section-label">文档预览</span
              ><span class="muted">未加载</span>
            </div>
            <div class="upload-stage">
              <div class="document-illustration">
                <span class="back-sheet"></span>
                <div class="front-sheet">
                  <AppIcon name="text" /><span></span><span></span><span></span>
                </div>
                <span class="illustration-plus">+</span>
              </div>
              <span class="eyebrow">从一份文档开始</span>
              <h2>将 PDF 拖入此处</h2>
              <p>提取内容、保护文档，或添加签名与水印</p>
              <button class="primary" @click="input?.click()">
                <AppIcon name="upload" />选择 PDF 文件</button
              ><small>PDF 格式 · 最大 50 MiB · 200 页</small>
            </div></template
          >
        </section>
        <aside class="operation-panel">
          <form class="parameters" @submit.prevent="submit">
            <div class="panel-heading">
              <h2>处理参数</h2>
              <span class="step-number">01</span>
            </div>
            <fieldset :disabled="busy">
              <label class="field"
                >文档打开密码 <span class="optional">可选</span
                ><input
                  v-model="password"
                  type="password"
                  autocomplete="off"
                  placeholder="仅加密文档需要"
              /></label>
              <label
                v-if="['text', 'tables', 'watermark'].includes(tool)"
                class="field"
                >页码范围<input
                  v-model="form.pages"
                  placeholder="全部页，例如 1,3-5"
              /></label>
              <template v-if="tool === 'text'"
                ><div class="segmented">
                  <button
                    type="button"
                    :class="{ active: mode === 'extract' }"
                    @click="mode = 'extract'"
                  >
                    文本提取</button
                  ><button
                    type="button"
                    :class="{ active: mode === 'search' }"
                    @click="mode = 'search'"
                  >
                    正则搜索
                  </button>
                </div>
                <label v-if="mode === 'extract'" class="check-field"
                  ><input
                    v-model="form.preserveParagraphs"
                    type="checkbox"
                  />保留段落结构</label
                ><template v-else
                  ><label class="field"
                    >正则表达式<input
                      v-model="form.pattern"
                      required
                      placeholder="输入搜索表达式" /></label
                  ><label class="field"
                    >匹配方式<select v-model.number="form.flags">
                      <option :value="0">区分大小写</option>
                      <option :value="1">忽略大小写</option>
                      <option :value="4">多行匹配</option>
                      <option :value="2">点匹配换行</option>
                    </select></label
                  ></template
                ></template
              >
              <p v-if="tool === 'tables'" class="parameter-note">
                识别横竖线构成的规则表格，支持单元格文本及 CSV 导出。
              </p>
              <label v-if="tool === 'images'" class="field"
                >图片格式<select v-model="form.format">
                  <option>PNG</option>
                  <option>JPG</option></select
                ><small>压缩包包含原始流、转换图片和来源清单。</small></label
              >
              <template v-if="tool === 'encrypt'"
                ><label class="field"
                  >用户密码<input
                    v-model="form.userPassword"
                    type="password"
                    autocomplete="new-password" /></label
                ><label class="field"
                  >所有者密码<input
                    v-model="form.ownerPassword"
                    required
                    type="password"
                    autocomplete="new-password" /></label
                ><label class="field"
                  >加密强度<select v-model.number="form.keyLength">
                    <option :value="256">AES 256 位</option>
                    <option :value="128">AES 128 位</option>
                  </select></label
                >
                <div class="permissions">
                  <label class="check-field"
                    ><input
                      v-model="form.allowPrint"
                      type="checkbox"
                    />允许打印</label
                  ><label class="check-field"
                    ><input
                      v-model="form.allowCopy"
                      type="checkbox"
                    />允许复制内容</label
                  ><label class="check-field"
                    ><input
                      v-model="form.allowModify"
                      type="checkbox"
                    />允许修改</label
                  >
                </div></template
              >
              <template v-if="tool === 'signatures'"
                ><div class="segmented">
                  <button
                    type="button"
                    :class="{ active: mode === 'verify' }"
                    @click="mode = 'verify'"
                  >
                    验证</button
                  ><button
                    type="button"
                    :class="{ active: mode === 'sign' }"
                    @click="mode = 'sign'"
                  >
                    签署</button
                  ><button
                    type="button"
                    :class="{ active: mode === 'remove' }"
                    @click="mode = 'remove'"
                  >
                    移除
                  </button>
                </div>
                <template v-if="mode === 'sign'"
                  ><label class="field"
                    >签名证书（PKCS#12）<FilePicker
                      v-model="certificate"
                      label="签名证书（PKCS#12）"
                      accept=".p12,.pfx"
                      required /></label
                  ><label class="field"
                    >证书密码<input
                      v-model="form.certificatePassword"
                      type="password"
                      autocomplete="off" /></label
                  ><label class="field"
                    >证书别名 <span class="optional">可选</span
                    ><input v-model="form.alias" /></label
                  ><label class="field"
                    >签署原因<input v-model="form.reason" /></label
                  ><label class="field"
                    >签署地点<input v-model="form.location" /></label></template
                ><template v-else-if="mode === 'verify'"
                  ><label class="field"
                    >信任证书 <span class="optional">可选</span
                    ><FilePicker
                      v-model="trust"
                      label="信任证书"
                      accept=".cer,.crt,.pem"
                  /></label>
                  <p class="parameter-note">
                    分别检查完整性、后续修订、证书有效期与信任。未提供信任证书时不判定可信，未启用在线吊销检查。
                  </p></template
                ><template v-else
                  ><p class="parameter-note">
                    生成移除全部签名的新副本。仅支持未加密文档，原文件保持不变。
                  </p>
                  <label class="check-field"
                    ><input
                      v-model="form.confirmRemoval"
                      type="checkbox"
                      required
                    />确认移除副本中的全部签名</label
                  ></template
                ></template
              >
              <template v-if="tool === 'watermark'"
                ><label class="field"
                  >水印类型<select v-model="form.type">
                    <option value="TEXT">文字水印</option>
                    <option value="IMAGE">图片水印</option>
                  </select></label
                ><template v-if="form.type === 'TEXT'"
                  ><label class="field"
                    >水印文字<input
                      v-model="form.text"
                      required
                      maxlength="512"
                      placeholder="输入水印文字"
                  /></label>
                  <div class="field-grid">
                    <label class="field"
                      >字号<input
                        v-model.number="form.fontSize"
                        type="number"
                        min="1"
                        max="500"
                        required /></label
                    ><label class="field"
                      >颜色<input v-model="form.color" type="color"
                    /></label></div></template
                ><template v-else
                  ><label class="field"
                    >水印图片<FilePicker
                      v-model="watermark"
                      label="水印图片"
                      accept="image/png,image/jpeg"
                      required
                  /></label>
                  <div class="field-grid">
                    <label class="field"
                      >最大宽度<input
                        v-model.number="form.width"
                        type="number"
                        min="1"
                        max="10000" /></label
                    ><label class="field"
                      >最大高度<input
                        v-model.number="form.height"
                        type="number"
                        min="1"
                        max="10000"
                    /></label></div
                ></template>
                <div class="field-grid">
                  <label class="field"
                    >位置 X<input
                      v-model.number="form.x"
                      type="number"
                      step="any"
                      required /></label
                  ><label class="field"
                    >位置 Y<input
                      v-model.number="form.y"
                      type="number"
                      step="any"
                      required /></label
                  ><label class="field"
                    >角度<input
                      v-model.number="form.angle"
                      type="number"
                      min="-360"
                      max="360"
                      required /></label
                  ><label class="field"
                    >不透明度<input
                      v-model.number="form.opacity"
                      type="number"
                      min="0"
                      max="1"
                      step="0.05"
                      required
                  /></label>
                </div>
                <p class="parameter-note">
                  位置采用 PDF 原始坐标轴，通常以左下角为原点。
                </p></template
              >
              <p v-if="tool === 'info'" class="parameter-note">
                读取文档标准及自定义元数据、书签层级、批注与高亮位置。
              </p>
              <button
                type="submit"
                class="primary run-button"
                :disabled="!file || busy"
              >
                <span v-if="busy" class="spinner"></span
                ><AppIcon
                  v-else
                  :name="tool === 'signatures' ? 'signatures' : 'arrow'"
                />{{ busy ? "正在处理…" : action }}
              </button>
            </fieldset>
          </form>
          <div v-if="error" class="error-banner" role="alert">{{ error }}</div>
          <section class="results" aria-live="polite">
            <ResultPanel
              :tool="tool"
              :mode="mode"
              :result="result"
              :completed="completed"
              :busy="busy"
              @locate="selection = $event"
              @csv="csv"
            /><a
              v-if="download"
              class="download-card"
              :href="download.url"
              :download="download.name"
              ><span class="download-icon"><AppIcon name="download" /></span
              ><span
                ><strong>下载处理结果</strong
                ><small>{{ download.name }}</small></span
              ><AppIcon name="arrow"
            /></a>
          </section>
        </aside>
      </div>
      <footer class="workspace-footer">
        <span>源文件保持不变</span><span>PDF 文档处理系统</span>
      </footer>
    </main>
  </div>
  <AccountDialog :open="accountOpen" @close="accountOpen = false" />
  <AiAssistant
    :file="file"
    :password="password"
    @locate="selection = $event"
    @restore="selected"
    @password="password = $event"
  />
</template>

<script setup lang="ts">
import { ref, shallowRef, watch, onBeforeUnmount, nextTick } from "vue";
import {
  getDocument,
  GlobalWorkerOptions,
  type PDFDocumentProxy,
  type PDFDocumentLoadingTask,
} from "pdfjs-dist";
import workerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";
import PdfPage from "./PdfPage.vue";
import type { Selection } from "../types/pdf";
GlobalWorkerOptions.workerSrc = workerUrl;
const props = defineProps<{
  file: File | null;
  password: string;
  selection: Selection | null;
}>();
const page = ref(1),
  total = ref(0),
  zoom = ref(1),
  error = ref(""),
  loading = ref(false);
const document = shallowRef<PDFDocumentProxy | null>(null);
const scrollArea = ref<HTMLElement | null>(null);
let task: PDFDocumentLoadingTask | null = null,
  version = 0;
function navigate(target: number) {
  const root = scrollArea.value,
    el = root?.querySelector<HTMLElement>(`[data-page="${target}"]`);
  if (root && el) {
    root.scrollTop +=
      el.getBoundingClientRect().top - root.getBoundingClientRect().top;
    page.value = target;
  }
}
function scrolled() {
  const root = scrollArea.value;
  if (!root) return;
  const top = root.getBoundingClientRect().top;
  const pages = Array.from(root.querySelectorAll<HTMLElement>("[data-page]"));
  const current = pages.find(
    (el) => el.getBoundingClientRect().bottom > top + 80,
  );
  if (current) page.value = Number(current.dataset.page);
}
function destroy() {
  version++;
  void task?.destroy();
  task = null;
  document.value = null;
  total.value = 0;
}
async function load() {
  destroy();
  error.value = "";
  if (!props.file) return;
  const current = version;
  loading.value = true;
  try {
    const data = new Uint8Array(await props.file.arrayBuffer());
    if (current !== version) return;
    const loadingTask = getDocument({ data, password: props.password });
    task = loadingTask;
    const pdf = await loadingTask.promise;
    if (current !== version) {
      await loadingTask.destroy();
      return;
    }
    document.value = pdf;
    total.value = pdf.numPages;
    page.value =
      props.selection?.page && props.selection.page <= pdf.numPages
        ? props.selection.page
        : 1;
    await nextTick();
    navigate(page.value);
  } catch (reason) {
    if (current === version)
      error.value =
        reason instanceof Error && reason.name === "PasswordException"
          ? (reason as Error & { code?: number }).code === 1
            ? "此 PDF 需要打开密码，请输入后点击确认密码"
            : "密码不正确，请修改后重新确认"
          : "预览加载失败，请检查 PDF 文件";
  } finally {
    if (current === version) loading.value = false;
  }
}
watch(() => [props.file, props.password], load, { immediate: true });
watch(
  () => props.selection,
  async () => {
    if (props.selection) {
      await nextTick();
      navigate(props.selection.page);
    }
  },
);
onBeforeUnmount(destroy);
</script>
<template>
  <div class="preview-toolbar">
    <span class="section-label">文档预览</span>
    <div v-if="total" class="preview-controls">
      <button
        aria-label="上一页"
        :disabled="page <= 1"
        @click="navigate(page - 1)"
      >
        ‹</button
      ><span>{{ page }} / {{ total }}</span
      ><button
        aria-label="下一页"
        :disabled="page >= total"
        @click="navigate(page + 1)"
      >
        ›</button
      ><select v-model.number="zoom" aria-label="预览缩放">
        <option :value="0.75">75%</option>
        <option :value="1">100%</option>
        <option :value="1.25">125%</option>
        <option :value="1.5">150%</option>
      </select>
    </div>
  </div>
  <div ref="scrollArea" class="preview-scroll" @scroll.passive="scrolled">
    <p v-if="loading" class="preview-message">正在加载文档…</p>
    <p v-if="error" class="preview-message error" role="status">{{ error }}</p>
    <template v-if="document && !error">
      <PdfPage
        v-for="n in total"
        :key="version + ':' + n"
        :pdf="document"
        :number="n"
        :zoom="zoom"
        :selection="selection"
      />
    </template>
  </div>
</template>

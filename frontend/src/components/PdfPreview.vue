<script setup lang="ts">
import { ref, shallowRef, watch, onBeforeUnmount, nextTick } from "vue";
import {
  getDocument,
  GlobalWorkerOptions,
  type PDFDocumentProxy,
  type PDFDocumentLoadingTask,
  type RenderTask,
} from "pdfjs-dist";
import workerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";
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
  loading = ref(false),
  canvas = ref<HTMLCanvasElement | null>(null);
const dimensions = ref({ width: 0, height: 0 }),
  rectangles = ref<
    { left: number; top: number; width: number; height: number }[]
  >([]);
const document = shallowRef<PDFDocumentProxy | null>(null);
const scrollArea = ref<HTMLElement | null>(null);
let task: PDFDocumentLoadingTask | null = null,
  renderTask: RenderTask | null = null,
  version = 0,
  renderVersion = 0;
function destroy() {
  version++;
  renderVersion++;
  renderTask?.cancel();
  renderTask = null;
  void task?.destroy();
  task = null;
  document.value = null;
  total.value = 0;
  rectangles.value = [];
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
    await render();
  } catch (reason) {
    if (current === version)
      error.value = reason instanceof Error ? reason.message : "预览加载失败";
  } finally {
    if (current === version) loading.value = false;
  }
}
async function render() {
  const pdf = document.value;
  if (!pdf) return;
  const current = ++renderVersion;
  renderTask?.cancel();
  try {
    const pdfPage = await pdf.getPage(page.value);
    if (current !== renderVersion || !canvas.value) return;
    const viewport = pdfPage.getViewport({ scale: zoom.value });
    const ratio = window.devicePixelRatio || 1;
    dimensions.value = { width: viewport.width, height: viewport.height };
    canvas.value.width = Math.floor(viewport.width * ratio);
    canvas.value.height = Math.floor(viewport.height * ratio);
    renderTask = pdfPage.render({
      canvas: canvas.value,
      viewport,
      transform: ratio === 1 ? undefined : [ratio, 0, 0, ratio, 0, 0],
    });
    await renderTask.promise;
    if (current !== renderVersion) return;
    rectangles.value =
      props.selection?.page === page.value
        ? props.selection.bounds.map((r) => {
            const [x1, y1] = viewport.convertToViewportPoint(r.x, r.y);
            const [x2, y2] = viewport.convertToViewportPoint(
              r.x + r.width,
              r.y + r.height,
            );
            return {
              left: Math.min(x1!, x2!),
              top: Math.min(y1!, y2!),
              width: Math.abs(x2! - x1!),
              height: Math.abs(y2! - y1!),
            };
          })
        : [];
    await nextTick();
    if (current === renderVersion && rectangles.value.length) {
      scrollArea.value
        ?.querySelector<HTMLElement>(".highlight")
        ?.scrollIntoView({ block: "center", inline: "nearest" });
    }
  } catch (reason) {
    if (
      current === renderVersion &&
      !(
        reason instanceof Error && reason.name === "RenderingCancelledException"
      )
    )
      error.value = "页面渲染失败";
  }
}
watch(() => [props.file, props.password], load, { immediate: true });
watch([page, zoom], render);
watch(
  () => props.selection,
  () => {
    if (props.selection) page.value = props.selection.page;
    void render();
  },
);
onBeforeUnmount(destroy);
</script>
<template>
  <div class="preview-toolbar">
    <span class="section-label">文档预览</span>
    <div v-if="total" class="preview-controls">
      <button aria-label="上一页" :disabled="page <= 1" @click="page--">
        ‹</button
      ><span>{{ page }} / {{ total }}</span
      ><button aria-label="下一页" :disabled="page >= total" @click="page++">
        ›</button
      ><select v-model.number="zoom" aria-label="预览缩放">
        <option :value="0.75">75%</option>
        <option :value="1">100%</option>
        <option :value="1.25">125%</option>
        <option :value="1.5">150%</option>
      </select>
    </div>
  </div>
  <div ref="scrollArea" class="preview-scroll">
    <p v-if="loading" class="preview-message">正在加载文档…</p>
    <p v-if="error" class="preview-message error" role="status">{{ error }}</p>
    <div
      v-show="document && !error"
      class="paper"
      :style="{
        width: dimensions.width + 'px',
        height: dimensions.height + 'px',
      }"
    >
      <canvas
        ref="canvas"
        :style="{
          width: dimensions.width + 'px',
          height: dimensions.height + 'px',
        }"
        aria-label="PDF 页面预览"
      /><span
        v-for="(r, i) in rectangles"
        :key="i"
        class="highlight"
        :style="{
          left: r.left + 'px',
          top: r.top + 'px',
          width: r.width + 'px',
          height: r.height + 'px',
        }"
      ></span>
    </div>
  </div>
</template>

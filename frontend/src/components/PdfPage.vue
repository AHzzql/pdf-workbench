<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from "vue";
import type { PDFDocumentProxy, PDFPageProxy, RenderTask } from "pdfjs-dist";
import type { Selection } from "../types/pdf";
const props = defineProps<{
  pdf: PDFDocumentProxy;
  number: number;
  zoom: number;
  selection: Selection | null;
}>();
const element = ref<HTMLElement | null>(null),
  canvas = ref<HTMLCanvasElement | null>(null);
const size = ref({ width: 595, height: 842 }),
  visible = ref(false),
  error = ref("");
let pdfPage: PDFPageProxy | null = null,
  task: RenderTask | null = null,
  observer: IntersectionObserver | null = null,
  revision = 0;
let disposed = false;
const bounds = computed(() => {
  const width = size.value.width;
  if (!pdfPage || !width || props.selection?.page !== props.number) return [];
  const viewport = pdfPage.getViewport({ scale: props.zoom });
  return props.selection.bounds.map((r) => {
    const [x1, y1] = viewport.convertToViewportPoint(r.x, r.y),
      [x2, y2] = viewport.convertToViewportPoint(r.x + r.width, r.y + r.height);
    return {
      left: Math.min(x1!, x2!) + "px",
      top: Math.min(y1!, y2!) + "px",
      width: Math.abs(x2! - x1!) + "px",
      height: Math.abs(y2! - y1!) + "px",
    };
  });
});
async function render() {
  const current = ++revision;
  const previous = task;
  previous?.cancel();
  if (previous) {
    try {
      await previous.promise;
    } catch {
      /* Cancelled rendering releases its canvas. */
    }
  }
  if (current !== revision) return;
  if (!visible.value || !pdfPage || !canvas.value) {
    if (canvas.value) {
      canvas.value.width = 0;
      canvas.value.height = 0;
    }
    return;
  }
  const viewport = pdfPage.getViewport({ scale: props.zoom }),
    ratio = Math.min(window.devicePixelRatio || 1, 2);
  canvas.value.width = Math.floor(viewport.width * ratio);
  canvas.value.height = Math.floor(viewport.height * ratio);
  try {
    task = pdfPage.render({
      canvas: canvas.value,
      viewport,
      transform: ratio === 1 ? undefined : [ratio, 0, 0, ratio, 0, 0],
    });
    await task.promise;
    if (current === revision) error.value = "";
  } catch (e) {
    if (
      current === revision &&
      !(e instanceof Error && e.name === "RenderingCancelledException")
    )
      error.value = "页面渲染失败";
  }
}
watch(
  () => [props.zoom, visible.value],
  () => {
    void render();
  },
);
onMounted(async () => {
  try {
    const page = await props.pdf.getPage(props.number);
    if (disposed) return;
    pdfPage = page;
    const v = page.getViewport({ scale: 1 });
    size.value = { width: v.width, height: v.height };
    observer = new IntersectionObserver(
      (entries) => {
        visible.value = entries[0]?.isIntersecting ?? false;
      },
      { root: element.value?.closest(".preview-scroll"), rootMargin: "600px" },
    );
    if (element.value) observer.observe(element.value);
  } catch {
    error.value = "页面加载失败";
  }
});
onBeforeUnmount(() => {
  disposed = true;
  revision++;
  observer?.disconnect();
  task?.cancel();
});
</script>
<template>
  <div
    ref="element"
    class="paper"
    :data-page="number"
    :style="{
      width: size.width * zoom + 'px',
      height: size.height * zoom + 'px',
      marginBottom: '24px',
    }"
  >
    <canvas
      ref="canvas"
      :aria-label="'PDF 页面预览 第 ' + number + ' 页'"
      :style="{ width: '100%', height: '100%' }"
    />
    <span v-for="(r, i) in bounds" :key="i" class="highlight" :style="r"></span>
    <p v-if="error" role="status">{{ error }}</p>
  </div>
</template>

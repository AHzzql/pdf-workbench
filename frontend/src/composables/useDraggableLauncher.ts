import { computed, onBeforeUnmount, onMounted, ref, type Ref } from "vue";
export function useDraggableLauncher(element: Ref<HTMLElement | null>) {
  const position = ref<{ x: number; y: number } | null>(null);
  const dragging = ref(false);
  let start: {
    id: number;
    x: number;
    y: number;
    left: number;
    top: number;
  } | null = null;
  let suppressClick = false;
  function clamp(x: number, y: number) {
    const width = element.value?.offsetWidth || 54,
      height = element.value?.offsetHeight || 54;
    position.value = {
      x: Math.max(8, Math.min(x, window.innerWidth - width - 8)),
      y: Math.max(8, Math.min(y, window.innerHeight - height - 8)),
    };
  }
  function down(e: PointerEvent) {
    if (!e.isPrimary || e.button !== 0 || !element.value) return;
    suppressClick = false;
    const rect = element.value.getBoundingClientRect();
    start = {
      id: e.pointerId,
      x: e.clientX,
      y: e.clientY,
      left: rect.left,
      top: rect.top,
    };
    element.value.setPointerCapture(e.pointerId);
  }
  function move(e: PointerEvent) {
    if (!start || e.pointerId !== start.id) return;
    const dx = e.clientX - start.x,
      dy = e.clientY - start.y;
    if (!dragging.value && Math.hypot(dx, dy) < 5) return;
    dragging.value = true;
    suppressClick = true;
    clamp(start.left + dx, start.top + dy);
  }
  function end(e: PointerEvent) {
    if (!start || e.pointerId !== start.id) return;
    start = null;
    dragging.value = false;
    if (element.value?.hasPointerCapture(e.pointerId))
      element.value.releasePointerCapture(e.pointerId);
  }
  function click(e: MouseEvent, activate: () => void) {
    if (suppressClick && e.detail !== 0) {
      e.preventDefault();
      suppressClick = false;
      return;
    }
    suppressClick = false;
    activate();
  }
  function resize() {
    if (position.value) clamp(position.value.x, position.value.y);
  }
  onMounted(() => window.addEventListener("resize", resize));
  onBeforeUnmount(() => window.removeEventListener("resize", resize));
  const style = computed(() =>
    position.value
      ? {
          left: position.value.x + "px",
          top: position.value.y + "px",
          right: "auto",
          bottom: "auto",
        }
      : undefined,
  );
  return { style, dragging, down, move, end, click };
}

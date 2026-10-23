import { onBeforeUnmount } from "vue";
export function useResizeHandle(begin: () => (dx: number, dy: number) => void) {
  let active: {
    id: number;
    x: number;
    y: number;
    update: (dx: number, dy: number) => void;
    target: HTMLElement;
  } | null = null;
  function end(e: PointerEvent) {
    if (active?.id !== e.pointerId) return;
    const target = active.target;
    active = null;
    if (target.hasPointerCapture(e.pointerId))
      target.releasePointerCapture(e.pointerId);
  }
  function down(e: PointerEvent) {
    if (!e.isPrimary || e.button !== 0) return;
    e.preventDefault();
    const target = e.currentTarget as HTMLElement;
    active = {
      id: e.pointerId,
      x: e.clientX,
      y: e.clientY,
      update: begin(),
      target,
    };
    target.setPointerCapture(e.pointerId);
  }
  function move(e: PointerEvent) {
    if (active?.id === e.pointerId)
      active.update(e.clientX - active.x, e.clientY - active.y);
  }
  onBeforeUnmount(() => {
    if (active?.target.hasPointerCapture(active.id))
      active.target.releasePointerCapture(active.id);
    active = null;
  });
  return { down, move, end };
}

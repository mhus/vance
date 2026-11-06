import { onBeforeUnmount, watch, type Ref } from 'vue';
import type { VueFlowStore } from '@vue-flow/core';

/**
 * Keeps a drawing in view when its box changes size — a side panel opening
 * next to it, the window resizing. vue-flow fits only once, on init; without
 * this a node ends up behind the panel.
 *
 * Bind {@code onPaneReady} to the {@code @pane-ready} event of the
 * {@code VueFlow} and {@code canvas} to the element around it.
 */
export function useFitOnResize(canvas: Ref<HTMLElement | null>) {
  let flow: VueFlowStore | null = null;
  let frame = 0;
  const observer = new ResizeObserver(() => {
    cancelAnimationFrame(frame);
    frame = requestAnimationFrame(() => { void flow?.fitView({ padding: 0.1 }); });
  });

  watch(canvas, (el, previous) => {
    if (previous) observer.unobserve(previous);
    if (el) observer.observe(el);
  }, { immediate: true });

  onBeforeUnmount(() => {
    cancelAnimationFrame(frame);
    observer.disconnect();
  });

  return {
    onPaneReady(instance: VueFlowStore): void {
      flow = instance;
    },
  };
}

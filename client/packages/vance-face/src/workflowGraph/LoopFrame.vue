<script setup lang="ts">
/**
 * The frame drawn around the body of a loop: a dashed outline with the loop's name on its upper edge. It takes the
 * size it is given and takes no clicks, so what lies under it stays reachable.
 */
import { VIcon } from '@/components';

defineOptions({ name: 'LoopFrame' });

defineProps<{
  /** The name of the loop state whose body it surrounds. */
  loop: string;
}>();
</script>

<template>
  <div class="wf-frame">
    <span class="wf-frame-label"><VIcon name="refresh" /> {{ loop }}</span>
  </div>
</template>

<style scoped>
.wf-frame {
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  border: 1.5px dashed color-mix(in oklab, var(--color-accent) 70%, transparent);
  border-radius: 0.75rem;
  background: color-mix(in oklab, var(--color-accent) 5%, transparent);
  pointer-events: none;
}
.wf-frame-label {
  position: absolute;
  /* Above the outline, in the gap a frame leaves to the one around it: nothing the layout draws lies there. */
  top: -1.15rem;
  left: 0.4rem;
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  font-size: 0.7rem;
  opacity: 0.75;
  color: var(--color-accent);
}
</style>

<script setup lang="ts">
/**
 * Read-only renderer for `vance-workbook-index` on non-editor surfaces
 * (BlockView, e.g. a standalone read-only workpage). Static card — the
 * jump-to-index interaction only makes sense inside the workbook host.
 */
defineProps<{ attrs: Record<string, unknown> }>();
</script>

<template>
  <div class="wb-index-ro">🏠 Workbook-Index</div>
</template>

<style scoped>
.wb-index-ro {
  display: inline-block;
  padding: 0.5em 0.9em;
  border-radius: 0.5rem;
  border: 1px solid oklch(var(--bc) / 0.2);
  background: oklch(var(--bc) / 0.06);
  margin: 0.5em 0;
}
</style>

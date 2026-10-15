<script setup lang="ts">
// The one-line action bar on top of a view (corporate-design.md §6.2): left
// the context (back, title, mode switch), right the actions — icon buttons
// via VIconButton, at most one primary VButton, rarely-used ones in a `more`
// menu. 44 px high, a hairline below, no background of its own.
</script>

<template>
  <div class="v-toolbar">
    <slot />
    <span class="v-toolbar-grow" />
    <slot name="end" />
  </div>
</template>

<style scoped>
.v-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 44px;
  padding: 0 12px;
  border-bottom: 1px solid var(--v-separator);
  background: var(--v-surface);
}
.v-toolbar-grow { flex: 1 1 auto; min-width: 0; }
</style>

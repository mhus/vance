<script setup lang="ts">
import { computed } from 'vue';

/**
 * Read-only renderer for the built-in `vance-callout` block, used by
 * `BlockView` (non-editor surfaces). Mirrors the markup the hard-coded
 * BlockView callout case emitted; the `.vance-callout*` styles live
 * globally in BlockView.vue so the classes resolve there.
 */
const props = defineProps<{ attrs: Record<string, unknown> }>();

const severity = computed(() => String(props.attrs.severity ?? 'info').toLowerCase());
const title = computed(() => (props.attrs.title as string | null) ?? null);
const body = computed(() => (props.attrs.body as string | undefined) ?? '');

const severityClass = computed(() =>
  ['info', 'warn', 'error', 'success', 'note'].includes(severity.value)
    ? `vance-callout--${severity.value}`
    : 'vance-callout--info',
);
</script>

<template>
  <aside class="vance-callout" :class="severityClass">
    <div v-if="title" class="vance-callout__title">{{ title }}</div>
    <div class="vance-callout__body">{{ body }}</div>
  </aside>
</template>

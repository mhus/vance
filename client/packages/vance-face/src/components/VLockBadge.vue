<script setup lang="ts">
/**
 * Compact lock-state indicator. Renders nothing when {@code lockedFor}
 * is empty / null. Otherwise shows a 🔒 plus the locked writer-role
 * initials (e.g. {@code A·K} for {@code [AI, KIT]}, {@code A·U·K} for
 * full lock).
 *
 * <p>Title attribute carries the long-form tooltip ("Locked against:
 * AI, KIT"). Used in the Cortex editor header and the file-tree row;
 * the Properties panel uses the explicit three-checkbox UI instead.
 */
import { computed } from 'vue';
import type { WriterRole } from '@vance/generated';

const props = defineProps<{
  lockedFor?: WriterRole[] | null;
}>();

const roles = computed<WriterRole[]>(() => {
  const r = props.lockedFor ?? [];
  return [...r].sort();
});

const initials = computed<string>(() =>
  roles.value.map((r) => r.charAt(0)).join('·'),
);

const tooltip = computed<string>(() =>
  roles.value.length === 0
    ? ''
    : `Locked against: ${roles.value.join(', ')}`,
);
</script>

<template>
  <span
    v-if="roles.length > 0"
    :title="tooltip"
    class="inline-flex items-center gap-1 rounded bg-base-300 px-1.5 py-0.5 text-xs font-mono opacity-80"
  >
    <span aria-hidden="true">🔒</span>
    <span>{{ initials }}</span>
  </span>
</template>

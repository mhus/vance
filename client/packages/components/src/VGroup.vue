<script setup lang="ts">
// A block of an inset-grouped list (corporate-design.md §7.2): settings,
// properties, detail pages. White group on the grey ground, a small caps
// label above, an optional explanation below — no border, no shadow. Rows are
// VGroupRow.
interface Props {
  /** Small caps label above the group. */
  label?: string;
  /** Explanation below the group. */
  note?: string;
}

defineProps<Props>();
</script>

<template>
  <section class="v-group-section">
    <div v-if="label || $slots.label" class="v-group-label">
      <slot name="label">{{ label }}</slot>
    </div>
    <div class="v-group">
      <slot />
    </div>
    <p v-if="note || $slots.note" class="v-group-note">
      <slot name="note">{{ note }}</slot>
    </p>
  </section>
</template>

<style scoped>
.v-group-section { display: flex; flex-direction: column; }
.v-group-label {
  padding: 0 16px 6px;
  font-size: 11px;
  line-height: 14px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--v-label-secondary);
}
.v-group {
  background: var(--v-surface);
  border-radius: var(--v-radius-group);
  overflow: hidden;
}
.v-group-note {
  margin: 0;
  padding: 6px 16px 0;
  font-size: 12px;
  line-height: 16px;
  color: var(--v-label-secondary);
}
</style>

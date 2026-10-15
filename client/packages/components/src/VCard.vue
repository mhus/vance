<script setup lang="ts">
// A block in the flow (corporate-design.md §7.1): the surface with a hairline
// and the group radius — never a shadow, shadows belong to floating layers.
// On the grey window ground it reads as a group; on a white page the hairline
// keeps it apart.
interface Props {
  title?: string;
}

defineProps<Props>();
</script>

<template>
  <section class="v-card">
    <h2 v-if="title || $slots.header" class="v-card-title">
      <slot name="header">{{ title }}</slot>
    </h2>
    <slot />
    <div v-if="$slots.actions" class="v-card-actions">
      <slot name="actions" />
    </div>
  </section>
</template>

<style scoped>
.v-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 20px;
  border: 1px solid var(--v-separator);
  border-radius: var(--v-radius-group);
  background: var(--v-surface);
  color: var(--v-label);
}
.v-card-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 15px;
  line-height: 20px;
  font-weight: 600;
}
.v-card-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 4px;
}
</style>

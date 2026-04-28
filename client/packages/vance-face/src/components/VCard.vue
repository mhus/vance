<script setup lang="ts">
interface Props {
  title?: string;
}

defineProps<Props>();
</script>

<template>
  <div class="card bg-base-100 shadow-xl">
    <div class="card-body">
      <h2 v-if="title || $slots.header" class="card-title">
        <slot name="header">{{ title }}</slot>
      </h2>
      <slot />
      <div v-if="$slots.actions" class="card-actions justify-end mt-4">
        <slot name="actions" />
      </div>
    </div>
  </div>
</template>

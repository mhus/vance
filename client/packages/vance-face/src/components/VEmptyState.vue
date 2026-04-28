<script setup lang="ts">
interface Props {
  headline: string;
  body?: string;
}

defineProps<Props>();
</script>

<template>
  <div class="flex flex-col items-center justify-center text-center py-12 gap-3">
    <div v-if="$slots.icon" class="text-4xl opacity-60">
      <slot name="icon" />
    </div>
    <h3 class="text-lg font-semibold">{{ headline }}</h3>
    <p v-if="body" class="text-sm opacity-70 max-w-md">{{ body }}</p>
    <div v-if="$slots.action" class="mt-2">
      <slot name="action" />
    </div>
  </div>
</template>

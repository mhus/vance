<script setup lang="ts">
interface Props {
  label?: string;
}

withDefaults(defineProps<Props>(), { label: 'Back' });

defineEmits<{ (e: 'click'): void }>();
</script>

<template>
  <button type="button" class="btn btn-ghost btn-sm gap-1" @click="$emit('click')">
    <span aria-hidden="true">‹</span>
    <span>{{ label }}</span>
  </button>
</template>

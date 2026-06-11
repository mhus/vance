<script setup lang="ts">
import { computed } from 'vue';
import type { CortexDocument } from '../types';
import { resolveRenderer } from '../docTypeRegistry';

interface Props {
  document: CortexDocument;
}

const props = defineProps<Props>();

const emit = defineEmits<{
  (e: 'update', text: string): void;
}>();

const renderer = computed(() => resolveRenderer(props.document));
</script>

<template>
  <component
    :is="renderer.component"
    :document="document"
    @update="(text: string) => emit('update', text)"
  />
</template>

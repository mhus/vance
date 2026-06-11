<script setup lang="ts">
import type { CortexDocument } from '../types';

interface Props {
  tabs: CortexDocument[];
  activeTabId?: string | null;
}

defineProps<Props>();

const emit = defineEmits<{
  (e: 'select', id: string): void;
  (e: 'close', id: string): void;
}>();
</script>

<template>
  <div class="flex items-stretch border-b border-base-300 bg-base-100 overflow-x-auto">
    <div
      v-for="tab in tabs"
      :key="tab.id"
      :class="[
        'group flex items-center gap-1 px-3 py-1.5 border-r border-base-300 cursor-pointer text-sm whitespace-nowrap min-w-0',
        activeTabId === tab.id
          ? 'bg-base-200 font-semibold'
          : 'hover:bg-base-200/60 opacity-80',
      ]"
      @click="emit('select', tab.id)"
    >
      <span class="truncate max-w-xs">{{ tab.path }}</span>
      <span v-if="tab.dirty" class="opacity-60 text-xs">●</span>
      <button
        type="button"
        class="opacity-40 group-hover:opacity-100 hover:text-error px-1"
        title="close"
        @click.stop="emit('close', tab.id)"
      >✕</button>
    </div>
    <div v-if="tabs.length === 0" class="px-3 py-1.5 text-sm opacity-50">
      No documents open — pick one from the tree, or create a new file.
    </div>
  </div>
</template>

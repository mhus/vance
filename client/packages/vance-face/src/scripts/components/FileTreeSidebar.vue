<script setup lang="ts">
import { ref } from 'vue';
import { VButton } from '@/components';
import type { FolderNode } from '../types';
import FileTreeNode from './FileTreeNode.vue';

interface Props {
  root: FolderNode;
  activeFileId?: string | null;
}

defineProps<Props>();

const emit = defineEmits<{
  (e: 'open-file', id: string): void;
  (e: 'new-file', parentPath: string): void;
  (e: 'delete-file', id: string): void;
}>();

// Root + common script folders pre-expanded so /scripts/ is the
// default visible scope, but the user still sees the full project
// tree at /. Other path-prefixes collapse until clicked.
const expanded = ref<Set<string>>(new Set(['', 'scripts']));

function toggle(path: string): void {
  const next = new Set(expanded.value);
  if (next.has(path)) {
    next.delete(path);
  } else {
    next.add(path);
  }
  expanded.value = next;
}
</script>

<template>
  <div class="p-2 text-sm">
    <div class="flex items-center justify-between mb-2 px-1">
      <span class="font-semibold opacity-80">Files</span>
      <VButton size="sm" variant="ghost" @click="emit('new-file', '')">+ new</VButton>
    </div>
    <FileTreeNode
      :node="root"
      :depth="0"
      :active-file-id="activeFileId ?? null"
      :expanded="expanded"
      @toggle="toggle"
      @open-file="(id: string) => emit('open-file', id)"
      @new-file="(p: string) => emit('new-file', p)"
      @delete-file="(id: string) => emit('delete-file', id)"
    />
  </div>
</template>

<script setup lang="ts">
import type { FolderNode } from '../types';

interface Props {
  node: FolderNode;
  depth: number;
  activeFileId?: string | null;
  expanded: Set<string>;
}

const props = defineProps<Props>();

const emit = defineEmits<{
  (e: 'toggle', path: string): void;
  (e: 'open-file', id: string): void;
  (e: 'new-file', parentPath: string): void;
  (e: 'delete-file', id: string): void;
}>();

function isOpen(path: string): boolean {
  return path === '' || props.expanded.has(path);
}

function fileIcon(name: string): string {
  if (name.endsWith('.js') || name.endsWith('.mjs')) return 'JS';
  if (name.endsWith('.json')) return '{}';
  if (name.endsWith('.md')) return 'MD';
  if (name.endsWith('.yaml') || name.endsWith('.yml')) return 'Y';
  return '·';
}

function indentStyle(extra: number): Record<string, string> {
  return { paddingLeft: `${(props.depth + extra) * 12}px` };
}
</script>

<template>
  <div>
    <button
      v-if="node.path !== ''"
      type="button"
      class="w-full text-left px-2 py-1 hover:bg-base-200 rounded flex items-center gap-1"
      :style="indentStyle(0)"
      @click="emit('toggle', node.path)"
    >
      <span class="opacity-50 w-3 inline-block text-xs">{{ isOpen(node.path) ? '▾' : '▸' }}</span>
      <span>📁</span>
      <span class="truncate">{{ node.name }}</span>
    </button>

    <template v-if="isOpen(node.path)">
      <FileTreeNode
        v-for="child in node.children"
        :key="`f:${child.path}`"
        :node="child"
        :depth="depth + 1"
        :active-file-id="activeFileId ?? null"
        :expanded="expanded"
        @toggle="(p: string) => emit('toggle', p)"
        @open-file="(id: string) => emit('open-file', id)"
        @new-file="(p: string) => emit('new-file', p)"
        @delete-file="(id: string) => emit('delete-file', id)"
      />
      <div
        v-for="file in node.files"
        :key="`x:${file.id}`"
        :class="[
          'group flex items-center gap-1 px-2 py-1 hover:bg-base-200 rounded cursor-pointer',
          activeFileId === file.id ? 'bg-base-200 font-semibold' : '',
        ]"
        :style="indentStyle(1).paddingLeft ? { paddingLeft: `${(depth + 1) * 12 + 4}px` } : {}"
      >
        <button
          type="button"
          class="flex-1 text-left flex items-center gap-1 min-w-0"
          @click="emit('open-file', file.id)"
        >
          <span class="opacity-50 w-6 text-xs font-mono">{{ fileIcon(file.name) }}</span>
          <span class="truncate">{{ file.name }}</span>
          <span v-if="file.dirty" class="opacity-50 text-xs">●</span>
        </button>
        <button
          type="button"
          class="opacity-0 group-hover:opacity-60 hover:opacity-100 px-1 text-xs"
          title="delete"
          @click.stop="emit('delete-file', file.id)"
        >✕</button>
      </div>
      <button
        v-if="node.path !== ''"
        type="button"
        class="block w-full text-left px-2 py-0.5 opacity-50 hover:opacity-100 text-xs"
        :style="{ paddingLeft: `${(depth + 1) * 12 + 4}px` }"
        @click="emit('new-file', node.path)"
      >+ new file here</button>
    </template>
  </div>
</template>

<script setup lang="ts">
import { nextTick, ref } from 'vue';
import type { FolderNode } from '../types';
import FileTreeNode from './FileTreeNode.vue';

interface Props {
  root: FolderNode;
  activeFileId?: string | null;
}

const props = defineProps<Props>();

const emit = defineEmits<{
  (e: 'open-file', id: string): void;
  (e: 'delete-file', id: string): void;
}>();

// Root pre-expanded so the project's top-level structure is visible
// immediately. Sub-folders collapse until clicked.
const expanded = ref<Set<string>>(new Set(['']));
const sidebarEl = ref<HTMLElement | null>(null);

function toggle(path: string): void {
  const next = new Set(expanded.value);
  if (next.has(path)) {
    next.delete(path);
  } else {
    next.add(path);
  }
  expanded.value = next;
}

// Walk the folder tree to collect the chain of folder paths that lead
// to {@code fileId} — the trail we need to add to {@link expanded} so
// the file row becomes visible. Returns null when the file is not in
// this tree (e.g. just deleted on the server).
function ancestorPathsFor(node: FolderNode, fileId: string): string[] | null {
  if (node.files.some((f) => f.id === fileId)) {
    return [node.path];
  }
  for (const child of node.children) {
    const sub = ancestorPathsFor(child, fileId);
    if (sub) return [node.path, ...sub];
  }
  return null;
}

function revealActiveFile(): void {
  const id = props.activeFileId;
  if (!id) return;
  const ancestors = ancestorPathsFor(props.root, id);
  if (!ancestors) return;
  const next = new Set(expanded.value);
  for (const p of ancestors) next.add(p);
  expanded.value = next;
  void nextTick(() => {
    const safe = window.CSS?.escape ? window.CSS.escape(id) : id;
    const el = sidebarEl.value?.querySelector<HTMLElement>(`[data-file-id="${safe}"]`);
    el?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  });
}
</script>

<template>
  <div ref="sidebarEl" class="p-2 text-sm">
    <div class="mb-2 px-1 flex items-center gap-1">
      <span class="font-semibold opacity-80 flex-1">Documents</span>
      <button
        type="button"
        class="text-xs px-1.5 py-0.5 rounded opacity-60 enabled:hover:opacity-100 enabled:hover:bg-base-200 disabled:cursor-default"
        :disabled="!activeFileId"
        title="Reveal active file in tree"
        @click="revealActiveFile"
      >🎯</button>
    </div>
    <FileTreeNode
      :node="root"
      :depth="0"
      :active-file-id="activeFileId ?? null"
      :expanded="expanded"
      @toggle="toggle"
      @open-file="(id: string) => emit('open-file', id)"
      @delete-file="(id: string) => emit('delete-file', id)"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, inject, type Ref } from 'vue';
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
  (e: 'delete-file', id: string): void;
  /** Move an already-tracked document into a folder (internal D&D). */
  (e: 'move-file', payload: { id: string; targetFolder: string }): void;
  /** Drop OS files onto a folder (external D&D). */
  (e: 'upload-files', payload: { files: File[]; targetFolder: string }): void;
}>();

function isOpen(path: string): boolean {
  return path === '' || props.expanded.has(path);
}

function fileIcon(name: string): string {
  if (name.endsWith('.js') || name.endsWith('.mjs')) return 'JS';
  if (name.endsWith('.json')) return '{}';
  if (name.endsWith('.md')) return 'MD';
  if (name.endsWith('.yaml') || name.endsWith('.yml')) return 'Y';
  if (name.endsWith('.txt')) return 'T';
  return '·';
}

function indentStyle(extra: number): Record<string, string> {
  return { paddingLeft: `${(props.depth + extra) * 12}px` };
}

// ──────────────── Drag & Drop ────────────────
//
// File rows are draggable. Folder rows (including the synthetic root)
// are drop targets that accept either:
//
//   - another tracked file → emits {@code move-file}, the parent
//     translates to {@code store.moveFile(id, newPath)}.
//   - OS files from the desktop → emits {@code upload-files}, the
//     parent translates to {@code store.uploadExternalFile(file, folder)}.
//
// {@code application/vance-doc-id} is our private MIME — used to
// distinguish an internal drag from a cross-tab drag without leaking
// the document id into dataTransfer.types (which is observable during
// dragover, when the data itself is not yet readable).
//
// {@link dragOverPath} is a tree-wide shared ref (provided by the
// FileTreeSidebar) so that only the deepest folder under the cursor
// shows the highlight — child {@code dragover} overwrites the path,
// {@code drop} or document-level cleanup clears it.

const VANCE_DOC_MIME = 'application/vance-doc-id';
const dragOverPath = inject<Ref<string | null>>('cortexDragOverPath');
const folderDragOver = computed(
  () => dragOverPath?.value === props.node.path,
);

function setDragOver(path: string | null): void {
  if (dragOverPath) dragOverPath.value = path;
}

function onFileDragStart(ev: DragEvent, fileId: string): void {
  if (!ev.dataTransfer) return;
  ev.dataTransfer.setData(VANCE_DOC_MIME, fileId);
  // text/plain mirror keeps non-conforming targets (the URL bar, an
  // editor outside the tree) from claiming ownership of the drag.
  ev.dataTransfer.setData('text/plain', fileId);
  ev.dataTransfer.effectAllowed = 'move';
}

function isAcceptableDrag(ev: DragEvent): boolean {
  const types = ev.dataTransfer?.types;
  if (!types) return false;
  // {@code DataTransferItemList} reports OS files as "Files".
  return Array.from(types).some(
    (t) => t === VANCE_DOC_MIME || t === 'Files',
  );
}

function onFolderDragOver(ev: DragEvent): void {
  if (!isAcceptableDrag(ev)) return;
  ev.preventDefault();
  ev.stopPropagation();
  const isExternal = Array.from(ev.dataTransfer?.types ?? []).includes('Files');
  if (ev.dataTransfer) {
    ev.dataTransfer.dropEffect = isExternal ? 'copy' : 'move';
  }
  setDragOver(props.node.path);
}

function onFolderDrop(ev: DragEvent): void {
  if (!isAcceptableDrag(ev)) return;
  ev.preventDefault();
  ev.stopPropagation();
  setDragOver(null);
  const dt = ev.dataTransfer;
  if (!dt) return;
  const docId = dt.getData(VANCE_DOC_MIME);
  if (docId) {
    emit('move-file', { id: docId, targetFolder: props.node.path });
    return;
  }
  const files = Array.from(dt.files ?? []);
  if (files.length > 0) {
    emit('upload-files', { files, targetFolder: props.node.path });
  }
}
</script>

<template>
  <div
    :class="[
      'rounded',
      folderDragOver ? 'ring-2 ring-primary/40 bg-primary/5' : '',
    ]"
    @dragover="onFolderDragOver"
    @drop="onFolderDrop"
  >
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
        @delete-file="(id: string) => emit('delete-file', id)"
        @move-file="(payload) => emit('move-file', payload)"
        @upload-files="(payload) => emit('upload-files', payload)"
      />
      <div
        v-for="file in node.files"
        :key="`x:${file.id}`"
        :data-file-id="file.id"
        draggable="true"
        :class="[
          'group flex items-center gap-1 px-2 py-1 hover:bg-base-200 rounded cursor-pointer',
          activeFileId === file.id ? 'bg-base-200 font-semibold' : '',
        ]"
        :style="{ paddingLeft: `${(depth + 1) * 12 + 4}px` }"
        @dragstart="(ev: DragEvent) => onFileDragStart(ev, file.id)"
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
    </template>
  </div>
</template>

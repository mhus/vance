<script setup lang="ts">
import { computed, inject, type Ref } from 'vue';
import { VIcon, VIconButton, accentColorDotClass } from '@/components';
import DocumentIcon from '@/document/DocumentIcon.vue';
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

interface MovePayload { id: string; targetFolder: string }
interface UploadPayload { files: File[]; targetFolder: string }
function onChildMove(payload: MovePayload): void { emit('move-file', payload); }
function onChildUpload(payload: UploadPayload): void { emit('upload-files', payload); }

function isOpen(path: string): boolean {
  return path === '' || props.expanded.has(path);
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
      class="tree-row w-full text-left px-2 py-1 rounded flex items-center gap-1.5"
      :style="indentStyle(0)"
      :data-folder-path="node.path"
      @click="emit('toggle', node.path)"
    >
      <VIcon :name="isOpen(node.path) ? 'expand' : 'forward'" class="tree-chevron" />
      <VIcon name="folder" class="tree-icon" />
      <span class="truncate">{{ node.name }}</span>
      <VIcon v-if="node.loading" name="loading" class="tree-icon animate-spin" :aria-label="$t('cortex.tree.loading')" />
      <span
        v-else-if="node.error"
        class="tree-error"
        :title="node.error"
      ><VIcon name="warning" /></span>
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
        @move-file="onChildMove"
        @upload-files="onChildUpload"
      />
      <div
        v-for="file in node.files"
        :key="`x:${file.id}`"
        :data-file-id="file.id"
        draggable="true"
        :class="[
          'tree-row group flex items-center gap-1 px-2 py-1 rounded cursor-pointer',
          activeFileId === file.id ? 'tree-row-active' : '',
        ]"
        :style="{ paddingLeft: `${(depth + 1) * 12 + 4}px` }"
        @dragstart="(ev: DragEvent) => onFileDragStart(ev, file.id)"
      >
        <button
          type="button"
          class="flex-1 text-left flex items-center gap-1 min-w-0"
          @click="emit('open-file', file.id)"
        >
          <DocumentIcon :path="file.name" />
          <span
            v-if="file.color"
            class="size-2 rounded-full flex-shrink-0"
            :class="accentColorDotClass(file.color)"
            :aria-label="`color ${file.color}`"
          />
          <span class="truncate">{{ file.name }}</span>
          <span v-if="file.dirty" class="tree-dirty" :title="$t('cortex.shell.unsaved')" />
        </button>
        <VIconButton
          icon="delete"
          size="sm"
          danger
          class="opacity-0 group-hover:opacity-100"
          :label="$t('cortex.tree.delete')"
          @click.stop="emit('delete-file', file.id)"
        />
      </div>

      <!--
        A folder holds more files than one page carries. Stated rather than
        dropped: silence here reads as "the folder ends" and made documents
        look deleted. Not a "load more" button in this iteration — say the
        truth first, paging inside a folder can follow.
      -->
      <div
        v-if="node.moreFiles > 0"
        class="px-2 py-1 text-xs opacity-60 italic"
        :style="{ paddingLeft: `${(depth + 1) * 12 + 4}px` }"
        :title="`Use search or the Documents view to reach the remaining ${node.moreFiles}`"
      >… {{ node.moreFiles }} more not shown</div>

      <!--
        Distinguishes "opened and empty" from "opened, still loading" and from
        "opened, the request failed". Three states that look identical as an
        empty folder, and only one of them means the folder is empty.
      -->
      <div
        v-else-if="node.loading"
        class="px-2 py-1 text-xs opacity-50 italic"
        :style="{ paddingLeft: `${(depth + 1) * 12 + 4}px` }"
      >{{ $t('cortex.tree.loadingText') }}</div>
      <div
        v-else-if="node.error"
        class="px-2 py-1 text-xs text-error"
        :style="{ paddingLeft: `${(depth + 1) * 12 + 4}px` }"
      >{{ node.error }}</div>
      <div
        v-else-if="node.loaded && node.children.length === 0 && node.files.length === 0"
        class="px-2 py-1 text-xs opacity-40 italic"
        :style="{ paddingLeft: `${(depth + 1) * 12 + 4}px` }"
      >{{ $t('cortex.tree.empty') }}</div>
    </template>
  </div>
</template>

<style scoped>
/* corporate-design §7.3 — tree rows on tokens. */
.tree-row:hover { background: var(--v-fill); }
.tree-row-active { background: var(--v-selection); color: var(--v-accent-text); font-weight: 500; }
.tree-chevron, .tree-icon { color: var(--v-label-tertiary); }
.tree-error { color: var(--v-danger); display: inline-flex; }
.tree-dirty { display: inline-block; width: 6px; height: 6px; border-radius: 3px; background: var(--v-label-tertiary); }
</style>

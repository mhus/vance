<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, provide, ref, watch } from 'vue';
import type { FolderNode } from '../types';
import { useCortexStore } from '../stores/cortexStore';
import FileTreeNode from './FileTreeNode.vue';

interface Props {
  root: FolderNode;
  activeFileId?: string | null;
  /**
   * Path of the active document. Needed alongside the id because revealing it
   * has to load the folders on the way there, and a folder that was never
   * opened is in no tree to search.
   */
  activeFilePath?: string | null;
  /**
   * When true, auto-reveal the active file in the tree whenever it changes
   * (or the tree (re)loads) — the same effect as clicking the 🎯 button,
   * applied on every tab switch. Owned + persisted by EditorApp.
   */
  autoReveal?: boolean;
}

const props = defineProps<Props>();

const emit = defineEmits<{
  (e: 'open-file', id: string): void;
  (e: 'delete-file', id: string): void;
  (e: 'move-file', payload: { id: string; targetFolder: string }): void;
  (e: 'upload-files', payload: { files: File[]; targetFolder: string }): void;
  (e: 'reload'): void;
}>();

// Expansion lives in the store, not here: opening a folder is what loads it,
// so the set of open folders is state the store has to be able to restore
// after a reload — not a view detail of this component.
const store = useCortexStore();
const expanded = computed<Set<string>>(() => store.expanded);
const sidebarEl = ref<HTMLElement | null>(null);

// Tree-wide single drop-target — the deepest folder currently under
// the cursor during a drag. Shared across all FileTreeNode instances
// via provide/inject so that {@code dragover} on a child folder
// implicitly clears the parent's highlight (last-writer-wins).
const dragOverPath = ref<string | null>(null);
provide('cortexDragOverPath', dragOverPath);

// Safety net: an OS-originated drag has no element of ours to fire
// {@code dragend} on, and a drag the user cancels (Esc / drops outside
// our nodes) leaves no other hook to reset the highlight. Document-
// level {@code dragend}/{@code drop} listeners catch all of those — for
// drops on our own folder rows the inner handler has already cleared
// the path, so the no-op here is harmless.
function clearDragOver(): void {
  dragOverPath.value = null;
}
onMounted(() => {
  document.addEventListener('dragend', clearDragOver);
  document.addEventListener('drop', clearDragOver);
});
onBeforeUnmount(() => {
  document.removeEventListener('dragend', clearDragOver);
  document.removeEventListener('drop', clearDragOver);
});

/** Expanding loads the folder; the store owns both halves. */
function toggle(path: string): void {
  void store.toggleFolder(path);
}

interface MovePayload { id: string; targetFolder: string }
interface UploadPayload { files: File[]; targetFolder: string }
function onMoveFile(payload: MovePayload): void { emit('move-file', payload); }
function onUploadFiles(payload: UploadPayload): void { emit('upload-files', payload); }

/**
 * Bring the active document into view: load and expand the folders on the way
 * to it, then scroll to its row.
 *
 * <p>Driven by the document's <b>path</b>, not by searching the rendered tree.
 * With folders loading on demand, the file's folder is usually not in the tree
 * yet — a tree walk would find nothing and silently do nothing, which is what
 * "reveal" must never do.
 */
async function revealActiveFile(): Promise<void> {
  const id = props.activeFileId;
  const path = props.activeFilePath;
  if (!id || !path) return;
  await store.expandTo(path);
  await nextTick();
  const safe = window.CSS?.escape ? window.CSS.escape(id) : id;
  const el = sidebarEl.value?.querySelector<HTMLElement>(`[data-file-id="${safe}"]`);
  el?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
}

/**
 * Bring the <b>folder</b> of a document into view: expand the chain down
 * to it and scroll its row into sight, without touching the open tabs.
 *
 * <p>Driven from the breadcrumb — "where does this document live" is a
 * different question from "show me the document", which is why it does
 * not select a file.
 */
async function revealFolder(documentPath: string): Promise<void> {
  const idx = documentPath.lastIndexOf('/');
  if (idx < 0) return; // project root — nothing to expand
  const folder = documentPath.slice(0, idx);
  await store.expandTo(documentPath);
  await nextTick();
  const safe = window.CSS?.escape ? window.CSS.escape(folder) : folder;
  const el = sidebarEl.value?.querySelector<HTMLElement>(`[data-folder-path="${safe}"]`);
  el?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
}

defineExpose({ revealActiveFile, revealFolder });

// Auto-Target: reveal the active file whenever it changes, the mode gets
// switched on, or the tree (re)loads (root identity changes after a
// loadList). Manual 🎯 clicks work regardless of the mode.
// Not watching `props.root` any more: with lazy folders the tree object
// changes on every folder that loads, and revealing on each of those would
// re-walk (and re-request) the chain while the user is browsing elsewhere.
// The active file and the mode are the two things that actually mean "reveal".
watch(
  [() => props.activeFileId, () => props.autoReveal],
  () => {
    if (props.autoReveal && props.activeFileId) void revealActiveFile();
  },
  { immediate: true },
);
</script>

<template>
  <div ref="sidebarEl" class="p-2 text-sm">
    <div class="mb-2 px-1 flex items-center gap-1">
      <span class="font-semibold opacity-80 flex-1">{{ $t('cortex.tree.documents') }}</span>
      <button
        type="button"
        class="text-xs px-1.5 py-0.5 rounded opacity-60 enabled:hover:opacity-100 enabled:hover:bg-base-200 disabled:cursor-default"
        :disabled="!activeFileId"
        :title="$t('cortex.tree.reveal')"
        @click="revealActiveFile"
      >🎯</button>
      <button
        type="button"
        class="text-xs px-1.5 py-0.5 rounded opacity-60 hover:opacity-100 hover:bg-base-200"
        :title="$t('cortex.tree.reload')"
        @click="emit('reload')"
      >🔄</button>
    </div>
    <FileTreeNode
      :node="root"
      :depth="0"
      :active-file-id="activeFileId ?? null"
      :expanded="expanded"
      @toggle="toggle"
      @open-file="(id: string) => emit('open-file', id)"
      @delete-file="(id: string) => emit('delete-file', id)"
      @move-file="onMoveFile"
      @upload-files="onUploadFiles"
    />
  </div>
</template>

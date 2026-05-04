<script setup lang="ts">
import { computed, inject, nextTick, provide, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VueDraggable } from 'vue-draggable-plus';
import type { TreeDocument, TreeItem } from './treeItemsCodec';

/**
 * Recursive editor for `kind: tree` documents. The component runs in
 * two modes:
 *
 * 1. **Top-level** — the parent passes a {@code doc} prop. We build
 *    the editor state ({@link useTreeEditor}) here, provide it to
 *    descendants via {@code inject}, and render the tree's root list.
 *
 * 2. **Recursive** — when the component renders its children we pass
 *    the children array plus a {@code pathPrefix}. The recursive
 *    instance reads the editor state via {@code inject} and operates
 *    on its slice through that path.
 *
 * Mutations bubble up through the editor's {@code emit} callback as a
 * fresh {@link TreeDocument}; the parent re-serializes into the raw
 * body so the existing Save button writes the canonical form back.
 *
 * Spec: `specification/doc-kind-tree.md` (especially §5).
 */
defineOptions({ name: 'TreeView' });

const props = withDefaults(defineProps<{
  /** Top-level only — the full TreeDocument to edit. */
  doc?: TreeDocument | null;
  /** Recursive only — the children array of the parent item. */
  items?: TreeItem[];
  /** Recursive only — path of the *parent* item in the global tree.
   *  An item at index {@code idx} in {@code items} has the global
   *  path {@code [...pathPrefix, idx]}. Top-level uses {@code []}. */
  pathPrefix?: number[];
}>(), {
  doc: null,
  items: () => [],
  pathPrefix: () => [],
});

const emit = defineEmits<{
  (event: 'update:doc', doc: TreeDocument): void;
}>();

const { t } = useI18n();

// ── Editor state ────────────────────────────────────────────────────

interface TreeEditorApi {
  items: { value: TreeItem[] };
  editingPath: { value: number[] | null };
  editBuffer: { value: string };
  collapsed: { value: Set<string> };
  inputRefs: { value: Map<string, HTMLTextAreaElement> };
  emitChange: () => void;
  startEdit: (path: number[]) => void;
  cancelEdit: () => void;
  commitEdit: () => void;
  addSibling: (path: number[]) => void;
  addChild: (path: number[]) => void;
  addRoot: () => void;
  deleteAt: (path: number[]) => void;
  indentAt: (path: number[]) => void;
  outdentAt: (path: number[]) => void;
  toggleCollapsed: (path: number[]) => void;
  isCollapsed: (path: number[]) => boolean;
  isEditing: (path: number[]) => boolean;
  pathKey: (path: number[]) => string;
  prevDfsPath: (path: number[]) => number[] | null;
}

const isTopLevel = props.doc !== null;
const editor: TreeEditorApi = isTopLevel
  ? createEditor(props.doc!, (next) => emit('update:doc', next))
  : inject<TreeEditorApi>('treeEditor')!;

if (isTopLevel) {
  provide<TreeEditorApi>('treeEditor', editor);
}

/** The list of items to render at this level — bound via v-model
 *  on the underlying VueDraggable so reorder events flow back into
 *  the source array.
 *
 *  Top-level: read/write the editor's mutable ref directly.
 *  Recursive: read the {@code props.items} reference; when
 *  vue-draggable-plus emits a reordered array we splice it in place
 *  so the parent's same-array-reference picks up the new order. */
const renderItems = computed<TreeItem[]>({
  get: () => isTopLevel ? editor.items.value : props.items,
  set: (next) => {
    if (isTopLevel) {
      editor.items.value = next;
    } else {
      // Mutate in place — keeps the array reference stable so the
      // parent's editor.items still points at the same slice.
      const arr = props.items;
      arr.splice(0, arr.length, ...next);
    }
  },
});

function pathFor(idx: number): number[] {
  return [...props.pathPrefix, idx];
}

// ── Per-row keyboard / drag handlers (delegate to editor) ───────────

function onEditKeydown(event: KeyboardEvent, path: number[]): void {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    editor.commitEdit();
    editor.addSibling(path);
    return;
  }
  if (event.key === 'Escape') {
    event.preventDefault();
    editor.cancelEdit();
    return;
  }
  if (event.key === 'Tab') {
    event.preventDefault();
    // Commit the current text first so indent/outdent moves the
    // committed item, not a stale one.
    editor.commitEdit();
    if (event.shiftKey) {
      editor.outdentAt(path);
    } else {
      editor.indentAt(path);
    }
    return;
  }
  if (event.key === 'Backspace' && editor.editBuffer.value === '') {
    event.preventDefault();
    const prev = editor.prevDfsPath(path);
    editor.deleteAt(path);
    if (prev) {
      void nextTick(() => editor.startEdit(prev));
    }
  }
}

function autoGrow(event: Event): void {
  const el = event.target as HTMLTextAreaElement;
  el.style.height = 'auto';
  el.style.height = el.scrollHeight + 'px';
}

function registerInput(path: number[], el: Element | null): void {
  const key = editor.pathKey(path);
  if (el) {
    editor.inputRefs.value.set(key, el as HTMLTextAreaElement);
  } else {
    editor.inputRefs.value.delete(key);
  }
}

function onDragStart(): void {
  editor.cancelEdit();
}

function onDragEnd(): void {
  editor.emitChange();
}

/**
 * Per-level drag group. `vue-draggable-plus` only allows drops within
 * the same group, so giving each depth its own group blocks
 * cross-level drops at the library layer. Spec §5.4.
 */
const dragGroup = computed(() => `tree-level-${props.pathPrefix.length}`);

// ── Editor factory ─────────────────────────────────────────────────

function createEditor(
  initial: TreeDocument,
  onCommit: (doc: TreeDocument) => void,
): TreeEditorApi {
  const items = ref<TreeItem[]>(cloneItems(initial.items));
  const editingPath = ref<number[] | null>(null);
  const editBuffer = ref('');
  const collapsed = ref<Set<string>>(new Set());
  const inputRefs = ref<Map<string, HTMLTextAreaElement>>(new Map());

  // Keep the local copy in sync with external doc changes (e.g. Raw
  // tab edits flowing in). The codec is idempotent on round-trip, so
  // this watch firing after our own emit is a no-op effect.
  watch(
    () => initial.items,
    (next) => {
      items.value = cloneItems(next);
    },
    { deep: true },
  );

  function emitChange(): void {
    onCommit({
      kind: initial.kind || 'tree',
      items: items.value,
      extra: initial.extra,
    });
  }

  function getList(path: number[]): TreeItem[] | null {
    if (path.length === 0) return null;
    let curr = items.value;
    for (let i = 0; i < path.length - 1; i++) {
      const item = curr[path[i]];
      if (!item) return null;
      curr = item.children;
    }
    return curr;
  }

  function getItem(path: number[]): TreeItem | null {
    const list = getList(path);
    if (!list) return null;
    return list[path[path.length - 1]] ?? null;
  }

  function startEdit(path: number[]): void {
    editingPath.value = path;
    editBuffer.value = getItem(path)?.text ?? '';
    void nextTick(() => {
      const el = inputRefs.value.get(pathKey(path));
      if (el) {
        el.focus();
        el.setSelectionRange(el.value.length, el.value.length);
      }
    });
  }

  function cancelEdit(): void {
    editingPath.value = null;
    editBuffer.value = '';
  }

  function commitEdit(): void {
    if (!editingPath.value) return;
    const item = getItem(editingPath.value);
    if (item) {
      item.text = editBuffer.value;
    }
    editingPath.value = null;
    editBuffer.value = '';
    emitChange();
  }

  function addSibling(path: number[]): void {
    const list = getList(path);
    if (!list) {
      // path === []? push a top-level item.
      items.value.push({ text: '', children: [], extra: {} });
      const newPath = [items.value.length - 1];
      emitChange();
      void nextTick(() => startEdit(newPath));
      return;
    }
    const idx = path[path.length - 1];
    const newItem: TreeItem = { text: '', children: [], extra: {} };
    list.splice(idx + 1, 0, newItem);
    const newPath = [...path.slice(0, -1), idx + 1];
    emitChange();
    void nextTick(() => startEdit(newPath));
  }

  function addChild(path: number[]): void {
    const item = getItem(path);
    if (!item) return;
    const newItem: TreeItem = { text: '', children: [], extra: {} };
    item.children.unshift(newItem);
    // Expand the parent so the new child is visible.
    collapsed.value.delete(pathKey(path));
    collapsed.value = new Set(collapsed.value);
    const newPath = [...path, 0];
    emitChange();
    void nextTick(() => startEdit(newPath));
  }

  /** Add a new top-level item at the end of the root list. */
  function addRoot(): void {
    items.value.push({ text: '', children: [], extra: {} });
    const newPath = [items.value.length - 1];
    emitChange();
    void nextTick(() => startEdit(newPath));
  }

  function deleteAt(path: number[]): void {
    const list = getList(path);
    if (!list) return;
    const idx = path[path.length - 1];
    list.splice(idx, 1);
    cancelEdit();
    emitChange();
  }

  function indentAt(path: number[]): void {
    const list = getList(path);
    if (!list) return;
    const idx = path[path.length - 1];
    if (idx === 0) return; // first sibling can't be indented
    const prev = list[idx - 1];
    const me = list[idx];
    prev.children.push(me);
    list.splice(idx, 1);
    // Make sure the new parent is expanded so the moved item stays
    // visible.
    collapsed.value.delete(pathKey([...path.slice(0, -1), idx - 1]));
    collapsed.value = new Set(collapsed.value);
    // Re-focus the moved item.
    const newPath = [...path.slice(0, -1), idx - 1, prev.children.length - 1];
    if (editingPath.value && pathsEqual(editingPath.value, path)) {
      editingPath.value = newPath;
    }
    emitChange();
    void nextTick(() => {
      const el = inputRefs.value.get(pathKey(newPath));
      if (el) el.focus();
    });
  }

  function outdentAt(path: number[]): void {
    if (path.length <= 1) return; // top-level can't be outdented
    const myList = getList(path);
    if (!myList) return;
    const idx = path[path.length - 1];
    const myItem = myList[idx];

    const parentPath = path.slice(0, -1);
    const parentList = getList(parentPath);
    if (!parentList) return; // shouldn't happen for non-root
    const parentIdx = parentPath[parentPath.length - 1];

    myList.splice(idx, 1);
    parentList.splice(parentIdx + 1, 0, myItem);
    const newPath = [...parentPath.slice(0, -1), parentIdx + 1];
    if (editingPath.value && pathsEqual(editingPath.value, path)) {
      editingPath.value = newPath;
    }
    emitChange();
    void nextTick(() => {
      const el = inputRefs.value.get(pathKey(newPath));
      if (el) el.focus();
    });
  }

  function toggleCollapsed(path: number[]): void {
    const key = pathKey(path);
    const next = new Set(collapsed.value);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    collapsed.value = next;
  }

  function isCollapsed(path: number[]): boolean {
    return collapsed.value.has(pathKey(path));
  }

  function isEditing(path: number[]): boolean {
    return editingPath.value != null && pathsEqual(editingPath.value, path);
  }

  function pathKey(path: number[]): string {
    return path.join('.');
  }

  /** Previous item in pre-order DFS — used for Backspace-on-empty
   *  to focus the natural "before" neighbor. Returns null when we're
   *  the very first node. */
  function prevDfsPath(path: number[]): number[] | null {
    if (path.length === 0) return null;
    const idx = path[path.length - 1];
    if (idx === 0) {
      // Walk up to parent.
      const parentPath = path.slice(0, -1);
      return parentPath.length === 0 ? null : parentPath;
    }
    // Walk down the rightmost branch of the previous sibling.
    let cursorPath = [...path.slice(0, -1), idx - 1];
    while (true) {
      const item = getItem(cursorPath);
      if (!item || item.children.length === 0) break;
      if (collapsed.value.has(pathKey(cursorPath))) break;
      cursorPath = [...cursorPath, item.children.length - 1];
    }
    return cursorPath;
  }

  return {
    items,
    editingPath,
    editBuffer,
    collapsed,
    inputRefs,
    emitChange,
    startEdit,
    cancelEdit,
    commitEdit,
    addSibling,
    addChild,
    addRoot,
    deleteAt,
    indentAt,
    outdentAt,
    toggleCollapsed,
    isCollapsed,
    isEditing,
    pathKey,
    prevDfsPath,
  };
}

/** Tree-aware deep clone: preserves text/extra/children. */
function cloneItems(src: TreeItem[]): TreeItem[] {
  return src.map((it) => ({
    text: it.text,
    extra: { ...it.extra },
    children: cloneItems(it.children),
  }));
}

function pathsEqual(a: number[], b: number[]): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}
</script>

<template>
  <div :class="isTopLevel ? 'tree-edit' : 'tree-edit-nested'">
    <VueDraggable
      v-if="renderItems.length > 0"
      v-model="renderItems"
      tag="ul"
      class="tree-rows"
      :animation="150"
      :group="dragGroup"
      handle=".drag-handle"
      ghost-class="row--ghost"
      chosen-class="row--chosen"
      drag-class="row--drag"
      @start="onDragStart"
      @end="onDragEnd"
    >
      <li
        v-for="(item, idx) in renderItems"
        :key="idx"
        class="tree-row"
      >
        <div class="row-head">
          <span
            class="drag-handle"
            :title="t('documents.treeEditor.dragHandle')"
            aria-hidden="true"
          >⠿</span>
          <button
            v-if="item.children.length > 0"
            type="button"
            class="disclosure"
            :title="editor.isCollapsed(pathFor(idx))
              ? t('documents.treeEditor.expand')
              : t('documents.treeEditor.collapse')"
            @click="editor.toggleCollapsed(pathFor(idx))"
          >{{ editor.isCollapsed(pathFor(idx)) ? '▸' : '▾' }}</button>
          <span v-else class="disclosure-spacer" aria-hidden="true" />

          <textarea
            v-if="editor.isEditing(pathFor(idx))"
            :ref="(el) => registerInput(pathFor(idx), el as Element | null)"
            v-model="editor.editBuffer.value"
            class="edit-input"
            rows="1"
            @blur="editor.commitEdit()"
            @keydown="onEditKeydown($event, pathFor(idx))"
            @input="autoGrow"
          />
          <button
            v-else
            type="button"
            class="text"
            :title="t('documents.treeEditor.clickToEdit')"
            @click="editor.startEdit(pathFor(idx))"
          >
            <span v-if="item.text" class="text-content">{{ item.text }}</span>
            <span v-else class="text-empty">{{ t('documents.treeEditor.emptyItem') }}</span>
          </button>

          <button
            type="button"
            class="row-action"
            :title="t('documents.treeEditor.addChild')"
            @click="editor.addChild(pathFor(idx))"
          >＋</button>
          <button
            type="button"
            class="row-action row-delete"
            :title="t('documents.treeEditor.deleteItem')"
            @click="editor.deleteAt(pathFor(idx))"
          >✕</button>
        </div>

        <TreeView
          v-if="item.children.length > 0 && !editor.isCollapsed(pathFor(idx))"
          :items="item.children"
          :path-prefix="pathFor(idx)"
        />
      </li>
    </VueDraggable>

    <!-- Add-root only on the top-level instance. Nested instances
         use the per-item ＋ button instead. -->
    <div v-if="isTopLevel" class="add-row">
      <button
        type="button"
        class="add-root"
        @click="editor.addRoot()"
      >+ {{ t('documents.treeEditor.addItem') }}</button>
    </div>
  </div>
</template>

<style scoped>
.tree-edit {
  font-size: 0.95rem;
}
.tree-edit-nested {
  /* Nested levels indent and get a left border to mark hierarchy. */
  padding-left: 1.5rem;
  border-left: 1px dashed hsl(var(--bc) / 0.15);
}
.tree-rows {
  list-style: none;
  padding: 0;
  margin: 0;
}
.tree-row {
  padding: 0;
}
.row-head {
  display: grid;
  grid-template-columns: 1rem 1.25rem 1fr auto auto;
  gap: 0.4rem;
  align-items: start;
  padding: 0.3rem 0.25rem;
  border-bottom: 1px solid hsl(var(--bc) / 0.06);
}
.drag-handle {
  cursor: grab;
  user-select: none;
  text-align: center;
  opacity: 0.25;
  padding-top: 0.1rem;
  font-size: 1rem;
  line-height: 1;
}
.drag-handle:hover { opacity: 0.7; }
.drag-handle:active { cursor: grabbing; }
.disclosure {
  background: transparent;
  border: 0;
  cursor: pointer;
  font-size: 0.85rem;
  line-height: 1;
  opacity: 0.55;
  padding: 0.15rem 0;
  text-align: center;
  user-select: none;
}
.disclosure:hover { opacity: 1; }
.disclosure-spacer {
  display: inline-block;
  width: 1.25rem;
}
.text {
  text-align: left;
  background: transparent;
  border: 1px dashed transparent;
  border-radius: 0.25rem;
  padding: 0.25rem 0.5rem;
  cursor: text;
  min-height: 1.6rem;
  width: 100%;
}
.text:hover {
  border-color: hsl(var(--bc) / 0.15);
  background: hsl(var(--bc) / 0.04);
}
.text-content {
  white-space: pre-wrap;
  word-break: break-word;
}
.text-empty {
  opacity: 0.4;
  font-style: italic;
}
.edit-input {
  background: transparent;
  border: 1px solid hsl(var(--p) / 0.4);
  border-radius: 0.25rem;
  padding: 0.25rem 0.5rem;
  font: inherit;
  color: inherit;
  width: 100%;
  resize: none;
  outline: none;
  overflow: hidden;
  min-height: 1.6rem;
}
.edit-input:focus {
  border-color: hsl(var(--p));
  box-shadow: 0 0 0 2px hsl(var(--p) / 0.2);
}
.row-action {
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 0.25rem 0.4rem;
  border-radius: 0.25rem;
  opacity: 0.4;
  font-size: 0.85rem;
  line-height: 1;
}
.row-action:hover { opacity: 1; background: hsl(var(--bc) / 0.06); }
.row-delete:hover {
  background: hsl(var(--er) / 0.15);
  color: hsl(var(--er));
}
.add-row {
  margin-top: 0.5rem;
  padding-top: 0.25rem;
}
.add-root {
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 0.4rem 0.6rem;
  font-size: 0.85rem;
  opacity: 0.7;
  border-radius: 0.25rem;
}
.add-root:hover {
  opacity: 1;
  background: hsl(var(--bc) / 0.06);
}
.row--ghost { opacity: 0.35; background: hsl(var(--p) / 0.08); }
.row--chosen { background: hsl(var(--bc) / 0.04); }
.row--drag {
  opacity: 0.95;
  background: hsl(var(--b1));
  box-shadow: 0 4px 14px hsl(var(--bc) / 0.15);
}
</style>

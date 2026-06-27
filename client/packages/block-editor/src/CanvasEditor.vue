<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue';
import { useEditor, EditorContent, BubbleMenu } from '@tiptap/vue-3';
import StarterKit from '@tiptap/starter-kit';
import TaskList from '@tiptap/extension-task-list';
import TaskItem from '@tiptap/extension-task-item';
import Image from '@tiptap/extension-image';
import Table from '@tiptap/extension-table';
import TableRow from '@tiptap/extension-table-row';
import TableCell from '@tiptap/extension-table-cell';
import TableHeader from '@tiptap/extension-table-header';
import Link from '@tiptap/extension-link';
import Placeholder from '@tiptap/extension-placeholder';
import GlobalDragHandle from 'tiptap-extension-global-drag-handle';

import { parseDocument } from './markdown/parser';
import { serializeDocument } from './markdown/serializer';
import { blocksToContent, contentToBlocks } from './markdown/proseMirror';
import {
  VanceCallout,
  VanceToggle,
  VanceLink,
  VanceDataview,
  VanceUnknownFence,
} from './extensions';
import { SlashCommands } from './SlashCommands';
import 'tippy.js/dist/tippy.css';

/**
 * Tiptap-based Canvas editor. Mount contract matches the kind-registry
 * (single `document` prop carrying the CortexDocument shape).
 *
 * Sprint-B feature set:
 * - StarterKit marks (bold/italic/code/strike) with Markdown
 *   round-trip via {@code ./markdown/inline.ts}.
 * - Link extension (Ctrl+K / `[text](url)` Markdown / Bubble-menu).
 * - Bubble-menu on selection — Bold / Italic / Code / Link / Strike.
 * - Placeholder hints on empty paragraphs ("Type '/' for commands").
 * - Global drag-handle: hover a block to grab the ⠿ gutter handle.
 * - Slash-menu modal triggered by toolbar "+" button OR by typing `/`
 *   (heuristic — full @tiptap/suggestion integration follows).
 */
const props = withDefaults(
  defineProps<{
    document: {
      id: string;
      path: string;
      projectId: string;
      title?: string | null;
      inlineText?: string | null;
      mimeType?: string | null;
    };
    source?: string | null;
    autoSaveMs?: number;
  }>(),
  { autoSaveMs: 2000 },
);

const emit = defineEmits<{
  (e: 'save', body: string): void;
  (e: 'dirty', dirty: boolean): void;
}>();

const dirty = ref(false);
let autoSaveTimer: ReturnType<typeof setTimeout> | null = null;

function scheduleAutoSave() {
  if (props.autoSaveMs <= 0) return;
  if (autoSaveTimer != null) clearTimeout(autoSaveTimer);
  autoSaveTimer = setTimeout(() => {
    autoSaveTimer = null;
    save();
  }, props.autoSaveMs);
}

function cancelAutoSave() {
  if (autoSaveTimer != null) {
    clearTimeout(autoSaveTimer);
    autoSaveTimer = null;
  }
}

const initial = computed(() => {
  const md = props.source ?? props.document.inlineText ?? '';
  const doc = parseDocument(md);
  return { doc, content: { type: 'doc', content: blocksToContent(doc.blocks) } };
});

const editor = useEditor({
  extensions: [
    StarterKit.configure({
      heading: { levels: [1, 2, 3] },
      codeBlock: { languageClassPrefix: 'language-' },
      // Visible drop indicator while dragging a block via the global
      // drag handle — without this the user has no way to tell where
      // the block will land.
      dropcursor: { color: '#3b82f6', width: 3 },
    }),
    TaskList,
    TaskItem.configure({ nested: false }),
    Image,
    Table.configure({ resizable: false }),
    TableRow,
    TableCell,
    TableHeader,
    Link.configure({
      openOnClick: false,
      autolink: true,
      HTMLAttributes: { rel: 'noopener noreferrer', target: '_blank' },
    }),
    Placeholder.configure({
      placeholder: ({ node }) => {
        if (node.type.name === 'heading') return 'Heading';
        if (node.type.name === 'paragraph') return "Type '/' for commands…";
        return '';
      },
      showOnlyCurrent: false,
    }),
    GlobalDragHandle.configure({
      dragHandleWidth: 20,
      scrollTreshold: 100,
    }),
    SlashCommands,
    VanceCallout,
    VanceToggle,
    VanceLink,
    VanceDataview,
    VanceUnknownFence,
  ],
  content: initial.value.content,
  onUpdate: () => {
    if (!dirty.value) {
      dirty.value = true;
      emit('dirty', true);
    }
    scheduleAutoSave();
  },
});

watch(
  () => props.source,
  (next, prev) => {
    if (next === prev || !editor.value) return;
    cancelAutoSave();
    const parsed = parseDocument(next ?? '');
    editor.value.commands.setContent(
      { type: 'doc', content: blocksToContent(parsed.blocks) },
      false,
    );
    dirty.value = false;
    emit('dirty', false);
  },
);

function save() {
  if (!editor.value) return;
  cancelAutoSave();
  const json = editor.value.getJSON();
  const blocks = contentToBlocks(json.content as never[]);
  const fm = parseDocument(props.source ?? props.document.inlineText ?? '');
  const md = serializeDocument({
    title: fm.title ?? props.document.title ?? null,
    description: fm.description,
    blocks,
  });
  emit('save', md);
  dirty.value = false;
  emit('dirty', false);
}

function flush(): boolean {
  if (autoSaveTimer == null && !dirty.value) return false;
  save();
  return true;
}

// ── Inline mark helpers (bubble-menu) ─────────────────────────────

function toggleBold() { editor.value?.chain().focus().toggleBold().run(); }
function toggleItalic() { editor.value?.chain().focus().toggleItalic().run(); }
function toggleCode() { editor.value?.chain().focus().toggleCode().run(); }
function toggleStrike() { editor.value?.chain().focus().toggleStrike().run(); }

function setLink() {
  if (!editor.value) return;
  const previous = editor.value.getAttributes('link').href as string | undefined;
  const href = window.prompt('Link URL', previous ?? '');
  if (href === null) return;
  if (href === '') {
    editor.value.chain().focus().extendMarkRange('link').unsetLink().run();
    return;
  }
  editor.value
    .chain()
    .focus()
    .extendMarkRange('link')
    .setLink({ href })
    .run();
}

onBeforeUnmount(() => {
  if (autoSaveTimer != null) save();
  cancelAutoSave();
  editor.value?.destroy();
});

defineExpose({ save, flush });
</script>

<template>
  <div class="canvas-editor">
    <BubbleMenu
      v-if="editor"
      :editor="editor"
      :tippy-options="{ duration: 100, placement: 'top' }"
      class="canvas-editor__bubble-menu"
    >
      <button
        class="canvas-editor__bubble-btn"
        :class="{ 'canvas-editor__bubble-btn--active': editor.isActive('bold') }"
        :title="'Bold (Ctrl+B)'"
        @click="toggleBold"
      ><strong>B</strong></button>
      <button
        class="canvas-editor__bubble-btn"
        :class="{ 'canvas-editor__bubble-btn--active': editor.isActive('italic') }"
        :title="'Italic (Ctrl+I)'"
        @click="toggleItalic"
      ><em>i</em></button>
      <button
        class="canvas-editor__bubble-btn"
        :class="{ 'canvas-editor__bubble-btn--active': editor.isActive('strike') }"
        :title="'Strike'"
        @click="toggleStrike"
      ><s>S</s></button>
      <button
        class="canvas-editor__bubble-btn"
        :class="{ 'canvas-editor__bubble-btn--active': editor.isActive('code') }"
        :title="'Inline code'"
        @click="toggleCode"
      ><code>&lt;&gt;</code></button>
      <button
        class="canvas-editor__bubble-btn"
        :class="{ 'canvas-editor__bubble-btn--active': editor.isActive('link') }"
        :title="'Link (Ctrl+K)'"
        @click="setLink"
      >🔗</button>
    </BubbleMenu>

    <EditorContent :editor="editor" class="canvas-editor__body" />
  </div>
</template>

<style>
.canvas-editor {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--color-bg, #fff);
}
.canvas-editor__toolbar {
  display: flex;
  gap: 0.5rem;
  padding: 0.5rem 1rem;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
}
.canvas-editor__btn {
  padding: 0.25rem 0.75rem;
  border: 1px solid var(--color-border, #d1d5db);
  border-radius: 0.375rem;
  background: var(--color-button-bg, #f9fafb);
  cursor: pointer;
}
.canvas-editor__btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.canvas-editor__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 1.5rem 2rem 4rem;
}
.canvas-editor__body .ProseMirror {
  outline: none;
  max-width: 760px;
  margin: 0 auto;
  font-size: 1rem;
  line-height: 1.6;
}
.canvas-editor__body .ProseMirror h1 { font-size: 1.75rem; font-weight: 600; margin: 1.25em 0 0.5em; }
.canvas-editor__body .ProseMirror h2 { font-size: 1.4rem; font-weight: 600; margin: 1em 0 0.5em; }
.canvas-editor__body .ProseMirror h3 { font-size: 1.15rem; font-weight: 600; margin: 0.8em 0 0.4em; }
.canvas-editor__body .ProseMirror p { margin: 0.5em 0; }
.canvas-editor__body .ProseMirror ul {
  list-style-type: disc;
  padding-left: 1.5em;
  margin: 0.5em 0;
}
.canvas-editor__body .ProseMirror ol {
  list-style-type: decimal;
  padding-left: 1.5em;
  margin: 0.5em 0;
}
.canvas-editor__body .ProseMirror li > p { margin: 0; }
/* TaskList must stay bulletless — overrides the rule above. */
.canvas-editor__body .ProseMirror ul[data-type='taskList'] {
  list-style: none;
  padding: 0;
}
.canvas-editor__body .ProseMirror blockquote {
  border-left: 3px solid var(--color-border, #d1d5db);
  padding-left: 1em;
  color: var(--color-text-muted, #6b7280);
  margin: 0.75em 0;
}
.canvas-editor__body .ProseMirror pre {
  background: var(--color-code-bg, #f3f4f6);
  border-radius: 0.375rem;
  padding: 0.75em 1em;
  font-family: monospace;
  font-size: 0.9em;
  white-space: pre-wrap;
}
.canvas-editor__body .ProseMirror code {
  background: var(--color-code-bg, #f3f4f6);
  padding: 0.1em 0.3em;
  border-radius: 0.2em;
  font-size: 0.9em;
}
.canvas-editor__body .ProseMirror pre code {
  background: transparent;
  padding: 0;
}
.canvas-editor__body .ProseMirror a {
  color: var(--color-link, #2563eb);
  text-decoration: underline;
}
.canvas-editor__body .ProseMirror ul[data-type='taskList'] > li {
  display: flex;
  gap: 0.5em;
  align-items: flex-start;
}
.canvas-editor__body .ProseMirror hr {
  border: none;
  border-top: 1px solid var(--color-border, #e5e7eb);
  margin: 1.25em 0;
}

/* Placeholder — only shown on the currently focused empty paragraph,
   subtle so it doesn't clash with actual content. */
.canvas-editor__body .ProseMirror p.is-empty::before,
.canvas-editor__body .ProseMirror h1.is-empty::before,
.canvas-editor__body .ProseMirror h2.is-empty::before,
.canvas-editor__body .ProseMirror h3.is-empty::before {
  content: attr(data-placeholder);
  color: var(--color-text-muted, #9ca3af);
  float: left;
  height: 0;
  pointer-events: none;
}

/* Bubble menu */
.canvas-editor__bubble-menu {
  display: flex;
  gap: 0.125rem;
  padding: 0.25rem;
  background: var(--color-bg, #1f2937);
  color: #fff;
  border-radius: 0.375rem;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}
.canvas-editor__bubble-btn {
  background: transparent;
  border: none;
  color: #fff;
  padding: 0.25rem 0.5rem;
  border-radius: 0.25rem;
  cursor: pointer;
  font-size: 0.85rem;
  line-height: 1;
  min-width: 1.75rem;
}
.canvas-editor__bubble-btn:hover {
  background: rgba(255, 255, 255, 0.15);
}
.canvas-editor__bubble-btn--active {
  background: rgba(255, 255, 255, 0.25);
}

/* Global drag handle — small grey ⠿ icon left of the hovered block. */
.drag-handle {
  position: fixed;
  opacity: 0;
  transition: opacity 0.15s ease;
  width: 1rem;
  height: 1.25rem;
  z-index: 50;
  cursor: grab;
  background-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 16'><circle cx='3' cy='3' r='1.2' fill='%23999'/><circle cx='7' cy='3' r='1.2' fill='%23999'/><circle cx='3' cy='8' r='1.2' fill='%23999'/><circle cx='7' cy='8' r='1.2' fill='%23999'/><circle cx='3' cy='13' r='1.2' fill='%23999'/><circle cx='7' cy='13' r='1.2' fill='%23999'/></svg>");
  background-repeat: no-repeat;
  background-position: center;
  background-size: contain;
}
.drag-handle.hide { display: none; }
.canvas-editor:hover .drag-handle:not(.hide) { opacity: 1; }
.drag-handle:active { cursor: grabbing; }

/* Drop indicator while a block is being dragged. StarterKit's
   prosemirror-dropcursor draws this — we just make it loud enough
   to see across light/dark themes. */
.ProseMirror-dropcursor,
.prosemirror-dropcursor-block,
.prosemirror-dropcursor-inline {
  background: #3b82f6 !important;
  border: none !important;
}

/* Dim the source block while dragging so the visual hierarchy is
   "this is moving, here it'll land". Activated by the global-drag-
   handle extension via `dragging` class on the ProseMirror root. */
.ProseMirror.dragging .ProseMirror-selectednode {
  opacity: 0.35;
}

.vance-callout {
  border-left: 3px solid var(--vance-callout-color, #3b82f6);
  background: var(--vance-callout-bg, #eff6ff);
  border-radius: 0.375rem;
  padding: 0.75em 1em;
  margin: 0.75em 0;
}
.vance-callout--warn { --vance-callout-color: #f59e0b; --vance-callout-bg: #fffbeb; }
.vance-callout--error { --vance-callout-color: #ef4444; --vance-callout-bg: #fef2f2; }
.vance-callout--success { --vance-callout-color: #10b981; --vance-callout-bg: #ecfdf5; }
.vance-callout--note { --vance-callout-color: #6b7280; --vance-callout-bg: #f9fafb; }
.vance-callout__title { font-weight: 600; margin-bottom: 0.25em; }
.vance-callout__body { white-space: pre-wrap; }

.vance-toggle {
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 0.375rem;
  padding: 0.5em 0.75em;
  margin: 0.5em 0;
}
.vance-toggle__summary { font-weight: 500; cursor: pointer; }
.vance-toggle__body { margin-top: 0.5em; white-space: pre-wrap; }

.vance-link-card {
  display: block;
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 0.5rem;
  padding: 0.75em 1em;
  margin: 0.5em 0;
  text-decoration: none;
  color: inherit;
  background: var(--color-button-bg, #f9fafb);
}
.vance-link-card__title { font-weight: 600; }
.vance-link-card__description { font-size: 0.9em; color: var(--color-text-muted, #6b7280); }

.vance-dataview-stub {
  border: 1px dashed var(--color-border, #d1d5db);
  border-radius: 0.375rem;
  padding: 0.75em 1em;
  margin: 0.75em 0;
  background: var(--color-button-bg, #fafafa);
}
.vance-dataview-stub__label { font-weight: 600; font-size: 0.85em; color: var(--color-text-muted, #6b7280); }
.vance-dataview-stub__source { display: block; font-family: monospace; margin: 0.25em 0; }
.vance-dataview-stub__hint { font-size: 0.85em; color: var(--color-text-muted, #6b7280); }

.vance-unknown-fence {
  background: #fef2f2;
  border: 1px solid #fca5a5;
  border-radius: 0.375rem;
  padding: 0.5em 0.75em;
  margin: 0.5em 0;
  white-space: pre-wrap;
  font-family: monospace;
  font-size: 0.85em;
}
.vance-unknown-fence__label { font-weight: 600; margin-bottom: 0.25em; color: #b91c1c; }
</style>

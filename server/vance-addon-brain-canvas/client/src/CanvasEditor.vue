<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue';
import { useEditor, EditorContent } from '@tiptap/vue-3';
import StarterKit from '@tiptap/starter-kit';
import TaskList from '@tiptap/extension-task-list';
import TaskItem from '@tiptap/extension-task-item';
import Image from '@tiptap/extension-image';
import Table from '@tiptap/extension-table';
import TableRow from '@tiptap/extension-table-row';
import TableCell from '@tiptap/extension-table-cell';
import TableHeader from '@tiptap/extension-table-header';

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
import SlashMenu from './SlashMenu.vue';

/**
 * Tiptap-based Canvas editor. Mount contract matches the kind-registry
 * (single `document` prop carrying the CortexDocument shape).
 *
 * The editor holds the parsed block list as ProseMirror state; on
 * blur / debounced timer we serialise back to Markdown and call the
 * provided save handler. Live-WS reload is owned by the host (kind-
 * registry shell) which watches the document and re-invokes us with a
 * fresh body when the remote changes.
 */
const props = defineProps<{
  document: {
    id: string;
    path: string;
    projectId: string;
    title?: string | null;
    inlineText?: string | null;
    mimeType?: string | null;
  };
  source?: string | null;
}>();

const emit = defineEmits<{
  (e: 'save', body: string): void;
  (e: 'dirty', dirty: boolean): void;
}>();

const dirty = ref(false);
const slashOpen = ref(false);

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
    }),
    TaskList,
    TaskItem.configure({ nested: false }),
    Image,
    Table.configure({ resizable: false }),
    TableRow,
    TableCell,
    TableHeader,
    VanceCallout,
    VanceToggle,
    VanceLink,
    VanceDataview,
    VanceUnknownFence,
  ],
  content: initial.value.content,
  editorProps: {
    handleKeyDown(_, event) {
      if (event.key === '/' && !slashOpen.value) {
        slashOpen.value = true;
      }
      return false;
    },
  },
  onUpdate: () => {
    if (!dirty.value) {
      dirty.value = true;
      emit('dirty', true);
    }
  },
});

watch(
  () => props.source,
  (next, prev) => {
    if (next === prev || !editor.value) return;
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

function insertBlock(kind: string): void {
  if (!editor.value) return;
  slashOpen.value = false;
  const ed = editor.value;
  switch (kind) {
    case 'paragraph':
      ed.chain().focus().setParagraph().run();
      break;
    case 'heading-1':
      ed.chain().focus().toggleHeading({ level: 1 }).run();
      break;
    case 'heading-2':
      ed.chain().focus().toggleHeading({ level: 2 }).run();
      break;
    case 'heading-3':
      ed.chain().focus().toggleHeading({ level: 3 }).run();
      break;
    case 'todo':
      ed.chain().focus().toggleTaskList().run();
      break;
    case 'bullet':
      ed.chain().focus().toggleBulletList().run();
      break;
    case 'numbered':
      ed.chain().focus().toggleOrderedList().run();
      break;
    case 'quote':
      ed.chain().focus().toggleBlockquote().run();
      break;
    case 'code':
      ed.chain().focus().toggleCodeBlock().run();
      break;
    case 'divider':
      ed.chain().focus().setHorizontalRule().run();
      break;
    case 'callout':
      ed.chain()
        .focus()
        .insertContent({
          type: 'vanceCallout',
          attrs: { severity: 'info', title: 'Hinweis', body: '' },
        })
        .run();
      break;
    case 'toggle':
      ed.chain()
        .focus()
        .insertContent({
          type: 'vanceToggle',
          attrs: { summary: 'Details', body: '' },
        })
        .run();
      break;
    case 'link-card':
      ed.chain()
        .focus()
        .insertContent({
          type: 'vanceLink',
          attrs: { href: '', title: null, description: null },
        })
        .run();
      break;
    case 'dataview':
      ed.chain()
        .focus()
        .insertContent({ type: 'vanceDataview', attrs: { source: '' } })
        .run();
      break;
  }
}

onBeforeUnmount(() => {
  editor.value?.destroy();
});

defineExpose({ save });
</script>

<template>
  <div class="canvas-editor">
    <div class="canvas-editor__toolbar">
      <button class="canvas-editor__btn" :disabled="!dirty" @click="save">Save</button>
      <button class="canvas-editor__btn" @click="slashOpen = !slashOpen">+ Block</button>
    </div>
    <SlashMenu v-if="slashOpen" @pick="insertBlock" @close="slashOpen = false" />
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
.canvas-editor__body .ProseMirror ul, .canvas-editor__body .ProseMirror ol {
  padding-left: 1.5em;
  margin: 0.5em 0;
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
.canvas-editor__body .ProseMirror ul[data-type='taskList'] {
  list-style: none;
  padding: 0;
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

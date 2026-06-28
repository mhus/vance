<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount, onMounted } from 'vue';
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
import CodeBlockLowlight from '@tiptap/extension-code-block-lowlight';
import { createLowlight, common } from 'lowlight';
import 'highlight.js/styles/github.css';

const lowlight = createLowlight(common);

import { parseDocument } from './markdown/parser';
import { serializeDocument } from './markdown/serializer';
import { blocksToContent, contentToBlocks } from './markdown/proseMirror';
import {
  VanceCallout,
  VanceToggle,
  VanceLink,
  VanceDataview,
  VanceToc,
  VanceColumns,
  VanceColumn,
  VanceUnknownFence,
} from './extensions';
import { SlashCommands } from './SlashCommands';
import { HeadingAnchors } from './extensions/HeadingAnchors';
import { VueNodeViewRenderer } from '@tiptap/vue-3';
import VanceImageNodeView from './extensions/VanceImageNodeView.vue';
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
    /**
     * Image-upload callback. Invoked when the user drops or pastes an
     * image file into the editor. Returns the URL to embed (typically
     * the streaming-content URL of a freshly created `kind: image`
     * document, but any absolute URL works). Returning {@code null}
     * cancels the insert silently. The host owns the upload pipeline —
     * the editor itself doesn't know about projects / workspaces /
     * REST endpoints.
     */
    uploadImage?: (file: File) => Promise<string | null>;
    /**
     * Open an asset-picker UI. Invoked when the user selects the
     * "Image" slash command. The host (workspace addon) implements the
     * modal — it knows about workspace paths, asset folders, and how
     * to list existing images. The host inserts the picked image into
     * the editor via the {@link insertImageAt} helper exposed below.
     */
    openAssetPicker?: () => void;
    /**
     * Default project id for resolving {@code vance:} URIs that omit
     * an explicit authority (`vance:/path/foo.png?kind=image`). The
     * editor itself doesn't know which project a document lives in;
     * the host (workspace addon, cortex, …) injects it so the image
     * NodeView can turn {@code vance:} URIs into a real {@code <img src>}.
     */
    currentProjectId?: string;
    /**
     * Resolver called by the image NodeView when a {@code vance:} URI
     * needs to be turned into an HTTP {@code <img src>}. Host-provided
     * so the editor stays free of REST-client knowledge. Result is
     * cached per URI; return {@code null} on resolve-failure.
     */
    resolveImageSrc?: (vanceUri: string) => Promise<string | null>;
    /**
     * Open the host-provided link picker (similar contract to
     * {@code openAssetPicker}). The host renders a modal that lets the
     * user search documents in the project or paste a URL, then calls
     * back into the editor via {@code applyLink}. If the host omits
     * this prop, the editor falls back to a plain {@code window.prompt}.
     */
    openLinkPicker?: () => void;
    /**
     * Hide all floating editor menus (inline-mark bubble, image-width
     * bubble) while the host is showing a modal. Tippy's z-index is
     * ~9999 — without this flag the menus float above the modal.
     */
    suppressFloating?: boolean;
    /**
     * Host-side link-open callback. Invoked when the user
     * ctrl/cmd-clicks an {@code <a>} tag inside the editor — the
     * editor itself doesn't know how to route {@code vance:} URIs
     * (cortex tab-switch, project lookup, …). The host returns a
     * {@code true} to indicate it handled the navigation; a falsy
     * return lets the editor fall back to {@code window.open}.
     */
    openLink?: (href: string, openInNewTab: boolean) => boolean | void;
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

interface CanvasHeader {
  title: string | null;
  description: string | null;
  icon: string | null;
  cover: string | null;
}

// Front-matter state held independently from props.source so the host
// can patch icon/cover atomically (see updateHeader below) without
// rewriting activeMarkdown — which would trip the source-watch and
// rebuild the ProseMirror doc, dropping the cursor.
const currentHeader = ref<CanvasHeader>({
  title: initial.value.doc.title,
  description: initial.value.doc.description,
  icon: initial.value.doc.icon,
  cover: initial.value.doc.cover,
});

function imageFilesFrom(list: FileList | null | undefined): File[] {
  if (!list) return [];
  const out: File[] = [];
  for (let i = 0; i < list.length; i++) {
    const f = list.item(i);
    if (f && f.type.startsWith('image/')) out.push(f);
  }
  return out;
}

async function insertUploadedImages(files: File[], dropPos: number | null) {
  const ed = editor.value;
  if (!ed || !props.uploadImage || files.length === 0) return;
  for (const file of files) {
    let url: string | null = null;
    try {
      url = await props.uploadImage(file);
    } catch (e) {
      console.error('[CanvasEditor] image upload failed', e);
    }
    if (!url) continue;
    const chain = ed.chain().focus();
    if (dropPos != null) chain.insertContentAt(dropPos, { type: 'image', attrs: { src: url, alt: file.name } });
    else chain.setImage({ src: url, alt: file.name });
    chain.run();
  }
}

const editor = useEditor({
  extensions: [
    StarterKit.configure({
      heading: { levels: [1, 2, 3] },
      // Disable the StarterKit's plain code-block — we wire in the
      // lowlight-powered variant below for syntax highlighting.
      codeBlock: false,
      dropcursor: { color: '#3b82f6', width: 3 },
    }),
    CodeBlockLowlight.configure({
      lowlight,
      defaultLanguage: null,
      languageClassPrefix: 'language-',
      HTMLAttributes: { class: 'hljs' },
    }),
    TaskList,
    TaskItem.configure({ nested: false }),
    // Extend Tiptap's default Image with a `width` attribute holding the
    // selected preset, plus a Vue NodeView that resolves `vance:` URIs
    // to a real HTTP src. The on-disk markdown carries the `vance:` URI
    // (per the embedded-content spec), but `<img>` only speaks HTTP —
    // the NodeView does the lookup once + caches the resolved URL.
    Image.extend({
      addOptions() {
        return {
          ...this.parent?.(),
          resolveImageSrc: (uri: string) =>
            props.resolveImageSrc?.(uri) ?? Promise.resolve(null),
        };
      },
      addAttributes() {
        return {
          ...this.parent?.(),
          width: {
            default: null,
            parseHTML: (el) => el.getAttribute('data-width'),
            renderHTML: (attrs) => {
              if (!attrs.width) return {};
              return {
                'data-width': attrs.width,
                class: `canvas-image canvas-image--${attrs.width}`,
              };
            },
          },
        };
      },
      addNodeView() {
        return VueNodeViewRenderer(VanceImageNodeView as never);
      },
    }),
    Table.configure({ resizable: false }),
    TableRow,
    TableCell,
    TableHeader,
    // Link with a per-link `target` attribute (default Tiptap Link
    // ships `target` as a static HTMLAttributes setting only). We
    // need it per-link so the bubble-menu picker can let the user
    // pick "open in new tab" individually. The actual roundtrip
    // through Markdown drops the target attribute though — by
    // convention, on reload vance: URIs stay in the same tab and
    // external URLs open in a new tab.
    Link.extend({
      addAttributes() {
        return {
          ...this.parent?.(),
          target: {
            default: null,
            parseHTML: (el) => el.getAttribute('target'),
            renderHTML: (attrs) => attrs.target ? { target: attrs.target } : {},
          },
        };
      },
    }).configure({
      openOnClick: false,
      autolink: true,
      // Whitelist the `vance:` scheme so internal document links pass
      // Tiptap's URL validation. Without this, setLink({ href:
      // 'vance:/...' }) silently no-ops because the default allowed
      // set is { http, https, ftp, mailto, tel }.
      protocols: ['vance'],
      HTMLAttributes: { rel: 'noopener noreferrer' },
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
      // No customNodes — deliberately keep vanceColumns / vanceColumn
      // out so the drag handle never picks them up. Only their inner
      // block children (paragraphs, headings, lists, …) are
      // drag-sources. Re-arranging the whole columns block happens
      // through the slash menu, not drag-and-drop.
    }),
    SlashCommands,
    HeadingAnchors,
    VanceCallout,
    VanceToggle,
    VanceLink,
    VanceDataview,
    VanceToc,
    VanceColumns,
    VanceColumn,
    VanceUnknownFence,
  ],
  content: initial.value.content,
  editorProps: {
    handlePaste(_view, event) {
      const clipboard = event as ClipboardEvent;
      const files = imageFilesFrom(clipboard.clipboardData?.files);
      if (files.length === 0) return false;
      clipboard.preventDefault();
      void insertUploadedImages(files, null);
      return true;
    },
    /**
     * Notion-style link interaction: a plain click positions the
     * caret (so the user can edit the link text), while ⌘/Ctrl+click
     * opens the link. Without the modifier we leave ProseMirror to
     * its default selection handling.
     */
    handleClick(_view, _pos, event) {
      if (!(event.ctrlKey || event.metaKey)) return false;
      const target = event.target as HTMLElement | null;
      const anchor = target?.closest('a');
      if (!anchor) return false;
      const href = anchor.getAttribute('href');
      if (!href) return false;
      event.preventDefault();
      const openInNewTab = anchor.getAttribute('target') === '_blank';
      // Host-routing first — only the host knows how to resolve a
      // `vance:` URI to an in-app navigation (e.g. swap the cortex
      // tab to the linked document).
      const handled = props.openLink?.(href, openInNewTab);
      if (handled) return true;
      // Default: `window.open` for new-tab, in-place navigation for
      // same-tab. `vance:` URIs without a host handler will fail —
      // the browser can't navigate those — but that's the price of
      // not wiring a router.
      if (openInNewTab) window.open(href, '_blank', 'noopener,noreferrer');
      else window.location.href = href;
      return true;
    },
    /**
     * Reject drops that land inside a vanceColumns container but
     * OUTSIDE any vanceColumn child. Without this guard ProseMirror's
     * schema-fixup wraps the dropped block in a fresh vanceColumn,
     * silently growing the columns table by one slot per drop — exactly
     * the "4 columns appeared out of nowhere" symptom.
     *
     * File drops are routed through the separate DOM-level capture
     * listener (registered in onMounted) and skip this branch via the
     * `moved === false` short-circuit.
     */
    handleDrop(view, event, _slice, moved) {
      if (!moved) return false;
      const dragEvent = event as DragEvent;
      const coords = view.posAtCoords({
        left: dragEvent.clientX,
        top: dragEvent.clientY,
      });
      if (!coords) return false;
      const $pos = view.state.doc.resolve(coords.pos);
      let insideColumns = false;
      for (let d = $pos.depth; d >= 0; d--) {
        const name = $pos.node(d).type.name;
        if (name === 'vanceColumn') return false; // allowed
        if (name === 'vanceColumns') {
          insideColumns = true;
          break;
        }
      }
      if (insideColumns) {
        dragEvent.preventDefault();
        return true;
      }
      return false;
    },
  },
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
    currentHeader.value = {
      title: parsed.title,
      description: parsed.description,
      icon: parsed.icon,
      cover: parsed.cover,
    };
    dirty.value = false;
    emit('dirty', false);
  },
);

function save() {
  if (!editor.value) return;
  cancelAutoSave();
  const json = editor.value.getJSON();
  const blocks = contentToBlocks(json.content as never[]);
  const md = serializeDocument({
    title: currentHeader.value.title ?? props.document.title ?? null,
    description: currentHeader.value.description,
    icon: currentHeader.value.icon,
    cover: currentHeader.value.cover,
    blocks,
  });
  emit('save', md);
  dirty.value = false;
  emit('dirty', false);
}

/**
 * Patch one or more front-matter fields and immediately persist via
 * the regular save pipeline. The host calls this from icon/cover
 * edit UIs — it deliberately bypasses ProseMirror so the cursor and
 * selection in the body stay intact.
 */
function updateHeader(patch: Partial<CanvasHeader>) {
  currentHeader.value = { ...currentHeader.value, ...patch };
  save();
}

function flush(): boolean {
  if (autoSaveTimer == null && !dirty.value) return false;
  save();
  return true;
}

// ── Inline mark helpers (bubble-menu) ─────────────────────────────

// ── Image-width toolbar ───────────────────────────────────────────
type ImageWidthPreset = 'small' | 'medium' | 'large' | 'full';
const IMAGE_WIDTH_OPTIONS: ImageWidthPreset[] = ['small', 'medium', 'large', 'full'];

function isImageSelected(): boolean {
  return editor.value?.isActive('image') ?? false;
}
function currentImageWidth(): ImageWidthPreset | null {
  const w = editor.value?.getAttributes('image')?.width;
  return (IMAGE_WIDTH_OPTIONS as readonly string[]).includes(w)
    ? (w as ImageWidthPreset)
    : null;
}
function setImageWidth(width: ImageWidthPreset) {
  // `null` for the default 'full' so the markdown stays clean
  // (no `|full` suffix on the alt-text).
  editor.value
    ?.chain()
    .focus()
    .updateAttributes('image', { width: width === 'full' ? null : width })
    .run();
}

function toggleBold() { editor.value?.chain().focus().toggleBold().run(); }
function toggleItalic() { editor.value?.chain().focus().toggleItalic().run(); }
function toggleCode() { editor.value?.chain().focus().toggleCode().run(); }
function toggleStrike() { editor.value?.chain().focus().toggleStrike().run(); }

function setLink() {
  if (!editor.value) return;
  // If the host wired up a link picker, delegate. The host then calls
  // back through `applyLink` / `clearLink` on the exposed ref.
  if (props.openLinkPicker) {
    props.openLinkPicker();
    return;
  }
  // Fallback: plain prompt for hosts that don't provide a picker.
  const previous = editor.value.getAttributes('link').href as string | undefined;
  const href = window.prompt('Link URL', previous ?? '');
  if (href === null) return;
  if (href === '') { clearLink(); return; }
  applyLink(href);
}

/**
 * Apply a link to the current selection. {@code openInNewTab} maps to
 * the Tiptap link's {@code target} attribute. We default the target
 * by URL convention: {@code vance:} URIs stay in the same tab
 * (cortex-internal navigation), everything else opens in a new tab.
 *
 * Note: Markdown can't round-trip the {@code target} attribute, so an
 * explicit override is transient — on the next load the convention
 * default kicks back in. Per-link target persistence is a v2 concern.
 */
function applyLink(href: string, openInNewTab?: boolean) {
  if (!editor.value) return;
  if (!href || href.trim().length === 0) { clearLink(); return; }
  const isVance = href.startsWith('vance:');
  const useTarget = openInNewTab ?? !isVance;
  const attrs: { href: string; target: string | null } = {
    href,
    target: useTarget ? '_blank' : null,
  };
  editor.value
    .chain()
    .focus()
    .extendMarkRange('link')
    .setLink(attrs)
    .run();
}

function clearLink() {
  editor.value?.chain().focus().extendMarkRange('link').unsetLink().run();
}

function currentLinkHref(): string | null {
  const href = editor.value?.getAttributes('link').href;
  return typeof href === 'string' ? href : null;
}

// File-drop handler at the DOM level with `capture: true` so it fires
// before ProseMirror's own drop logic (and before any other extension's
// handleDOMEvents). External image drops route to uploadImage; anything
// else (internal block-reorder, plain-text drop, …) is left alone.
function onCaptureDragOver(e: DragEvent) {
  if (!props.uploadImage) return;
  const types = e.dataTransfer?.types ?? [];
  // Check `types` instead of files — `files` is restricted to the drop
  // event for security, but `types` is readable during dragover.
  if ([...types].some((t) => t === 'Files' || t.startsWith('image/'))) {
    // Only preventDefault — without it the browser rejects the drop
    // entirely. NO stopPropagation: ProseMirror's dropcursor extension
    // still needs to see the dragover so it can render the blue
    // drop-position indicator.
    e.preventDefault();
  }
}
function onCaptureDrop(e: DragEvent) {
  if (!props.uploadImage) return;
  const files = imageFilesFrom(e.dataTransfer?.files);
  if (files.length === 0) return;
  // On drop we DO stop propagation — once we have the file in hand,
  // ProseMirror's own drop handler must not also try to process it
  // (it would otherwise insert garbled text or navigate).
  e.preventDefault();
  e.stopPropagation();
  const ed = editor.value;
  if (!ed) return;
  const pos = ed.view.posAtCoords({ left: e.clientX, top: e.clientY });
  void insertUploadedImages(files, pos ? pos.pos : null);
}

function onAssetPickerEvent() {
  props.openAssetPicker?.();
}

onMounted(() => {
  const dom = editor.value?.view.dom;
  if (!dom) return;
  dom.addEventListener('dragover', onCaptureDragOver, { capture: true });
  dom.addEventListener('drop', onCaptureDrop, { capture: true });
  dom.addEventListener('vance:open-asset-picker', onAssetPickerEvent);
});

onBeforeUnmount(() => {
  if (autoSaveTimer != null) save();
  cancelAutoSave();
  const dom = editor.value?.view.dom;
  if (dom) {
    dom.removeEventListener('dragover', onCaptureDragOver, { capture: true });
    dom.removeEventListener('drop', onCaptureDrop, { capture: true });
    dom.removeEventListener('vance:open-asset-picker', onAssetPickerEvent);
  }
  editor.value?.destroy();
});

/**
 * Insert an image at the current cursor. Used by the host's asset
 * picker callback — it picks a stored image and calls this to drop the
 * URL into the editor.
 */
function insertImage(src: string, alt: string) {
  editor.value?.chain().focus().setImage({ src, alt }).run();
}

defineExpose({
  save, flush, insertImage, updateHeader,
  applyLink, clearLink, currentLinkHref,
  getHeader: () => currentHeader.value,
});
</script>

<template>
  <div class="canvas-editor">
    <BubbleMenu
      v-if="editor"
      :editor="editor"
      :tippy-options="{ duration: 100, placement: 'top' }"
      :should-show="() => !suppressFloating"
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

    <BubbleMenu
      v-if="editor"
      :editor="editor"
      :tippy-options="{ duration: 100, placement: 'top' }"
      :should-show="() => !suppressFloating && isImageSelected()"
      class="canvas-editor__bubble-menu canvas-editor__bubble-menu--image"
    >
      <button
        v-for="opt in IMAGE_WIDTH_OPTIONS"
        :key="opt"
        class="canvas-editor__bubble-btn"
        :class="{
          'canvas-editor__bubble-btn--active':
            (currentImageWidth() ?? 'full') === opt,
        }"
        :title="`Width: ${opt}`"
        @click="setImageWidth(opt)"
      >{{ opt === 'full' ? 'F' : opt[0].toUpperCase() }}</button>
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

/* Image width presets — picked from the bubble-menu toolbar. The
   classes ride on the <img> via the Image extension's renderHTML
   when `width` is set; `data-width` is the round-trip attribute. */
.ProseMirror img.canvas-image {
  display: block;
  height: auto;
}
.ProseMirror img.canvas-image--small {
  max-width: 25%;
}
.ProseMirror img.canvas-image--medium {
  max-width: 50%;
}
.ProseMirror img.canvas-image--large {
  max-width: 75%;
}

/* Links inside the editor — plain click positions the caret (Tiptap
   default), ⌘/Ctrl+click opens the URL (handled by editorProps.click
   above). The visual cue is in the cursor on modifier hover, plus a
   `title` injection on render so the browser tooltip says so. */
.ProseMirror a {
  color: var(--color-link, #3b82f6);
  text-decoration: underline;
  cursor: text;
}
.ProseMirror a:hover {
  text-decoration: underline;
  text-underline-offset: 2px;
}

/* Heading anchors — a tiny "#" button injected as a widget-decoration
   in front of every h1/h2/h3 by the HeadingAnchors extension. Hidden
   by default, fades in on heading hover. Click copies the URL with
   the heading's slug to the clipboard; the brief --copied class
   gives a visual confirmation. */
/* Anchor positions absolutely so it sits OUTSIDE the heading flow —
   that way the drag-handle (20px column at `rect.left - 20px`) and
   the anchor button can both live in the left gutter without
   overlapping. The button parks at the next slot further left. */
.ProseMirror h1,
.ProseMirror h2,
.ProseMirror h3 {
  position: relative;
}
.heading-anchor-btn {
  position: absolute;
  left: -2.6em;
  top: 50%;
  transform: translateY(-50%);
  display: inline-block;
  background: transparent;
  border: 0;
  cursor: pointer;
  padding: 0 0.3em;
  font-size: 0.7em;
  color: var(--color-text-muted, #6b7280);
  opacity: 0;
  transition: opacity 0.15s ease, color 0.15s ease;
  font-family: inherit;
  user-select: none;
  line-height: 1;
}
h1:hover > .heading-anchor-btn,
h2:hover > .heading-anchor-btn,
h3:hover > .heading-anchor-btn {
  opacity: 1;
}
.heading-anchor-btn:hover {
  color: var(--color-link, #3b82f6);
}
.heading-anchor-btn--copied {
  color: #16a34a !important;
  opacity: 1 !important;
}

/* Multi-column layout — the NodeView in @vance/block-editor handles
   the actual grid-template-columns + resize handles. CSS here only
   covers the dragging-target visuals. */

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

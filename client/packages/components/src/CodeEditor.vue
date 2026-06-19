<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Compartment, EditorState, type Extension } from '@codemirror/state';
import {
  EditorView,
  GutterMarker,
  gutter,
  highlightActiveLine,
  keymap,
  lineNumbers,
} from '@codemirror/view';
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands';
import {
  bracketMatching,
  defaultHighlightStyle,
  foldGutter,
  foldKeymap,
  indentOnInput,
  StreamLanguage,
  syntaxHighlighting,
} from '@codemirror/language';
import { markdown } from '@codemirror/lang-markdown';
import { yaml } from '@codemirror/lang-yaml';
import { json } from '@codemirror/lang-json';
import { javascript } from '@codemirror/lang-javascript';
import { python } from '@codemirror/lang-python';
import { html } from '@codemirror/lang-html';
import { css } from '@codemirror/lang-css';
import { xml } from '@codemirror/lang-xml';
import { sql } from '@codemirror/lang-sql';
import { java } from '@codemirror/lang-java';
import { shell } from '@codemirror/legacy-modes/mode/shell';
import { r } from '@codemirror/legacy-modes/mode/r';
import { followUpExtension, type FollowUpExtensionOptions } from './followUpExtension';

interface Props {
  modelValue: string;
  /**
   * Selects the syntax-highlighting language. See {@link languageFor}
   * for the full list — covers Markdown, JSON, YAML, JavaScript /
   * TypeScript, Python, Shell / Bash, R, HTML, CSS, XML, SQL, Java.
   * Unknown / blank mime-types fall back to plain text.
   */
  mimeType?: string | null;
  label?: string;
  /** Disabled state — read-only AND visually dimmed (form-disabled look). */
  disabled?: boolean;
  /**
   * Read-only without dimming — used when the same editor surface
   * shows generated / referenced code (rich-content blocks in chat,
   * embedded snippets in documents). Mutually exclusive with
   * {@link disabled} from a UX standpoint, but stacking is safe: both
   * map to the same underlying {@code EditorState.readOnly}.
   */
  readOnly?: boolean;
  /** Approximate visible-line count — drives min-height. */
  rows?: number;
  /**
   * Enables on-demand follow-up suggestions: pressing {@code Ctrl/Cmd
   * +Space} fetches a single suggestion (via the caller-provided
   * {@link FollowUpExtensionOptions.fetch}), shows it as a tooltip at
   * the cursor, and accepts it via {@code Tab}. {@code null}/absent
   * disables the feature entirely.
   *
   * <p>Only applied at editor-construction time; toggling at runtime
   * has no effect (the prop is read in {@code onMounted} only). For
   * conditional use (e.g. enable only for Markdown documents), the
   * host should conditionally render the {@code CodeEditor} with
   * {@code v-if="…"} so a fresh editor mounts when the toggle flips.
   */
  followUp?: FollowUpExtensionOptions | null;

  /**
   * Optional 1-based line numbers that should carry a sticky-note
   * anchor dot in a dedicated gutter to the left of the line-numbers
   * column. When provided (even as an empty array), a clickable
   * "notes gutter" is rendered. Reactive — updating the array
   * dispatches a state-effect that re-evaluates the markers.
   *
   * <p>Click handling — see the two emits:
   * <ul>
   *   <li>Click on a line that <em>is</em> in {@link noteLines} →
   *       {@code note-anchor-click(line)}.</li>
   *   <li>Click on a line that is <em>not</em> in {@link noteLines} →
   *       {@code note-gutter-click(line)} (host typically opens an
   *       "add note here" affordance).</li>
   * </ul>
   */
  noteLines?: number[];
}

const props = withDefaults(defineProps<Props>(), {
  rows: 20,
  disabled: false,
  readOnly: false,
});

/** Reported alongside the {@code selection-changed} event so hosts that
 *  need the highlighted text (e.g. Cortex' agent-tool surface) don't
 *  have to slice the model themselves. {@code text} is empty when the
 *  selection is a zero-length cursor; consumers should check
 *  {@code from === to} to distinguish "caret only" from "real range". */
export interface CodeEditorSelection {
  from: number;
  to: number;
  text: string;
}

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void;
  (e: 'selection-changed', selection: CodeEditorSelection): void;
  /** Click on an existing-note dot in the notes gutter. */
  (e: 'note-anchor-click', line: number): void;
  /** Click on the notes gutter at a line without a note. */
  (e: 'note-gutter-click', line: number): void;
}>();

const host = ref<HTMLDivElement | null>(null);
let view: EditorView | null = null;

// Compartments so language, read-only, and the optional notes gutter
// can be reconfigured at runtime without rebuilding the whole editor
// state.
const languageCompartment = new Compartment();
const readOnlyCompartment = new Compartment();
const notesGutterCompartment = new Compartment();

/** Marker rendered on lines that have an anchored note. */
class NoteAnchorMarker extends GutterMarker {
  override toDOM(): HTMLElement {
    const dot = document.createElement('span');
    dot.className = 'cm-note-anchor-dot';
    dot.textContent = '●';
    return dot;
  }
}
const SHARED_MARKER = new NoteAnchorMarker();

/**
 * Build the notes-gutter extension. When {@code lines} is undefined the
 * caller has opted out — return an empty extension array (no gutter
 * mounted). Otherwise we mount a dedicated gutter that renders dots on
 * the given line numbers and emits clicks to the host.
 */
function notesGutterExt(lines: number[] | undefined): Extension {
  if (lines === undefined) return [];
  const lineSet = new Set(lines);
  return gutter({
    class: 'cm-notes-gutter',
    lineMarker: (cmView, line) => {
      const ln = cmView.state.doc.lineAt(line.from).number;
      return lineSet.has(ln) ? SHARED_MARKER : null;
    },
    // Reserve column width even on lines without a dot so the gutter
    // doesn't jitter as the marker set changes.
    initialSpacer: () => SHARED_MARKER,
    domEventHandlers: {
      click: (cmView, line) => {
        const ln = cmView.state.doc.lineAt(line.from).number;
        if (lineSet.has(ln)) emit('note-anchor-click', ln);
        else emit('note-gutter-click', ln);
        return true;
      },
    },
  });
}

/** Map mime-type → CodeMirror language extension. */
function languageFor(mimeType: string | null | undefined): Extension {
  const mt = (mimeType ?? '').toLowerCase();
  // Doc / config formats.
  if (mt === 'text/markdown' || mt === 'text/x-markdown') return markdown();
  if (mt === 'application/json' || mt === 'text/json') return json();
  if (
    mt === 'application/yaml' ||
    mt === 'text/yaml' ||
    mt === 'application/x-yaml' ||
    mt === 'text/x-yaml'
  ) return yaml();
  // JavaScript + TypeScript — same package, TS via flag.
  if (
    mt === 'application/javascript' ||
    mt === 'text/javascript' ||
    mt === 'application/x-javascript'
  ) return javascript();
  if (
    mt === 'application/typescript' ||
    mt === 'text/typescript' ||
    mt === 'application/x-typescript'
  ) return javascript({ typescript: true });
  // Python.
  if (
    mt === 'text/x-python' ||
    mt === 'application/x-python' ||
    mt === 'text/python'
  ) return python();
  // Shell / Bash — legacy-mode StreamLanguage.
  if (
    mt === 'application/x-sh' ||
    mt === 'application/x-shellscript' ||
    mt === 'application/x-bash' ||
    mt === 'text/x-sh' ||
    mt === 'text/x-shellscript'
  ) return StreamLanguage.define(shell);
  // R — legacy-mode StreamLanguage.
  if (
    mt === 'text/x-r' ||
    mt === 'application/x-r' ||
    mt === 'text/x-rsrc'
  ) return StreamLanguage.define(r);
  // Web stack.
  if (mt === 'text/html' || mt === 'application/xhtml+xml') return html();
  if (mt === 'text/css') return css();
  if (mt === 'application/xml' || mt === 'text/xml') return xml();
  // SQL.
  if (mt === 'application/sql' || mt === 'text/x-sql' || mt === 'text/sql') return sql();
  // Java.
  if (
    mt === 'text/x-java-source' ||
    mt === 'text/x-java' ||
    mt === 'application/x-java-source'
  ) return java();
  return [];
}

function readOnlyExt(disabled: boolean): Extension {
  return EditorState.readOnly.of(disabled);
}

onMounted(() => {
  if (!host.value) return;
  const baseExtensions: Extension[] = [
    // Notes gutter sits to the LEFT of the line-numbers column so the
    // dot reads as an annotation *of* a line rather than an inline
    // numbering element. Mounted first so it lands leftmost.
    notesGutterCompartment.of(notesGutterExt(props.noteLines)),
    lineNumbers(),
    foldGutter(),
    history(),
    indentOnInput(),
    bracketMatching(),
    highlightActiveLine(),
    syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
    keymap.of([...defaultKeymap, ...historyKeymap, ...foldKeymap]),
    EditorView.lineWrapping,
    EditorView.updateListener.of((u) => {
      if (u.docChanged) {
        emit('update:modelValue', u.state.doc.toString());
      }
      // selectionSet covers both pointer + keyboard moves; docChanged
      // also implies a selection update (typing moves the caret), so
      // we re-emit either way. Hosts that don't care just ignore the
      // event — there's no per-keystroke listener on this side.
      if (u.selectionSet || u.docChanged) {
        const main = u.state.selection.main;
        const text = main.from === main.to
          ? ''
          : u.state.sliceDoc(main.from, main.to);
        emit('selection-changed', { from: main.from, to: main.to, text });
      }
    }),
    languageCompartment.of(languageFor(props.mimeType)),
    readOnlyCompartment.of(readOnlyExt(props.disabled || props.readOnly)),
  ];
  if (props.followUp) {
    baseExtensions.push(followUpExtension(props.followUp));
  }
  const state = EditorState.create({
    doc: props.modelValue ?? '',
    extensions: baseExtensions,
  });
  view = new EditorView({ state, parent: host.value });
});

watch(
  () => props.modelValue,
  (next) => {
    if (!view) return;
    const current = view.state.doc.toString();
    if (next === current) return;
    // External update (e.g. selecting a different document) —
    // replace the doc contents wholesale.
    view.dispatch({
      changes: { from: 0, to: current.length, insert: next ?? '' },
    });
  },
);

watch(
  () => props.mimeType,
  (mt) => {
    if (!view) return;
    view.dispatch({
      effects: languageCompartment.reconfigure(languageFor(mt)),
    });
  },
);

watch(
  // Deep watch — the array is reactive but identity-stable; clone to a
  // sorted joined string so adds/removes trigger a reconfigure even
  // when the reference is preserved.
  () => (props.noteLines ?? []).slice().sort((a, b) => a - b).join(','),
  () => {
    if (!view) return;
    view.dispatch({
      effects: notesGutterCompartment.reconfigure(notesGutterExt(props.noteLines)),
    });
  },
);

watch(
  () => [props.disabled, props.readOnly] as const,
  ([d, r]) => {
    if (!view) return;
    view.dispatch({
      effects: readOnlyCompartment.reconfigure(readOnlyExt(d || r)),
    });
  },
);

onBeforeUnmount(() => {
  view?.destroy();
  view = null;
});
</script>

<template>
  <label class="form-control w-full">
    <div v-if="label" class="label">
      <span class="label-text">{{ label }}</span>
    </div>
    <div
      ref="host"
      class="code-editor"
      :style="{ minHeight: `${rows * 1.5}rem` }"
      :class="{ 'code-editor--disabled': disabled }"
    />
  </label>
</template>

<style scoped>
.code-editor {
  border: 1px solid hsl(var(--bc) / 0.2);
  border-radius: 0.5rem;
  overflow: hidden;
  background: hsl(var(--b1));
}

.code-editor :deep(.cm-editor) {
  height: 100%;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 0.875rem;
}

.code-editor :deep(.cm-scroller) {
  font-family: inherit;
}

.code-editor--disabled {
  opacity: 0.6;
}

/* Sticky-note gutter: thin column to the left of line numbers. Cursor
 * is pointer on the whole column so the user notices the empty space
 * is interactive (click → new note at line). Dots sit centred. */
.code-editor :deep(.cm-notes-gutter) {
  min-width: 14px;
  cursor: pointer;
  background: transparent;
}
.code-editor :deep(.cm-notes-gutter .cm-gutterElement) {
  padding: 0 2px;
  text-align: center;
}
.code-editor :deep(.cm-note-anchor-dot) {
  color: #c8a90a;    /* yellow-amber, matches the sticky-note bg */
  font-size: 0.7rem;
  line-height: 1;
  display: inline-block;
  transform: translateY(-1px);
}
.code-editor :deep(.cm-notes-gutter .cm-gutterElement:hover .cm-note-anchor-dot),
.code-editor :deep(.cm-notes-gutter .cm-gutterElement:hover) {
  background: rgba(200, 169, 10, 0.12);
}
</style>

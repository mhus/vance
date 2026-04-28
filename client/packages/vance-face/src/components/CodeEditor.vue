<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Compartment, EditorState, type Extension } from '@codemirror/state';
import { EditorView, keymap, lineNumbers, highlightActiveLine } from '@codemirror/view';
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
  disabled?: boolean;
  /** Approximate visible-line count — drives min-height. */
  rows?: number;
}

const props = withDefaults(defineProps<Props>(), {
  rows: 20,
  disabled: false,
});

const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>();

const host = ref<HTMLDivElement | null>(null);
let view: EditorView | null = null;

// Compartments so language and read-only can be reconfigured at
// runtime without rebuilding the whole editor state.
const languageCompartment = new Compartment();
const readOnlyCompartment = new Compartment();

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
      if (!u.docChanged) return;
      emit('update:modelValue', u.state.doc.toString());
    }),
    languageCompartment.of(languageFor(props.mimeType)),
    readOnlyCompartment.of(readOnlyExt(props.disabled)),
  ];
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
  () => props.disabled,
  (d) => {
    if (!view) return;
    view.dispatch({
      effects: readOnlyCompartment.reconfigure(readOnlyExt(d)),
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
</style>

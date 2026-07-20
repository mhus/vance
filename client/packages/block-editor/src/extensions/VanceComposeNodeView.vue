<script setup lang="ts">
/**
 * NodeView for the {@code vance-compose} block — an inline Damogran compose
 * task cell.
 *
 * - **design mode**: an editable YAML textarea (the compose manifest) plus a
 *   "Run compose" button; edits are written back to the block's `yaml` attr.
 * - **work mode** (read-only page): a clean card — title/description + run
 *   button + outputs, *without* the YAML. Set `showSource: true` in the
 *   manifest (a UI-only flag the runner ignores) to reveal the source here too.
 *
 * Running calls the host `runCompose` with the current YAML and renders the
 * returned per-task outputs (images inline, everything else as a link). The
 * host resolves output content URLs, so this view needs no tenant/REST access.
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import jsyaml from 'js-yaml';
import { NodeViewWrapper } from '@tiptap/vue-3';
import type { Editor } from '@tiptap/core';
import type { Component } from 'vue';
import type { Node as ProseMirrorNode } from '@tiptap/pm/model';
import type { ComposeOutputView, ComposeRunResult } from './VanceCompose';
import {
  readComposeOutputs,
  writeComposeOutputs,
  readComposeRun,
  writeComposeRun,
  clearComposeManaged,
} from './composeOutputs';

const POLL_MS = 3000;

interface ExtensionOptions {
  runCompose?: ((yaml: string) => Promise<ComposeRunResult>) | null;
  pollCompose?: ((runId: string) => Promise<ComposeRunResult>) | null;
  cancelCompose?: ((runId: string) => Promise<ComposeRunResult>) | null;
  composeOutputComponent?: (() => Component | null) | null;
  projectId?: string;
}

const props = defineProps<{
  node: ProseMirrorNode;
  updateAttributes: (attrs: Record<string, unknown>) => void;
  getPos: () => number | undefined;
  editor: Editor;
  extension: { options: ExtensionOptions };
}>();

/** Host-injected output renderer (vance-face ComposeOutput) + its project id. */
const outputComponent = computed<Component | null>(
  () => props.extension.options.composeOutputComponent?.() ?? null,
);
const projectId = computed<string>(() => props.extension.options.projectId ?? '');

const yaml = computed(() => (props.node.attrs?.yaml as string | null) ?? '');

/**
 * Manifest-derived UI hints: title/description shown above the cell, and
 * `showSource` — a UI-only flag (ignored by the runner) that opts the
 * read-only page view into showing the raw YAML. Default is a clean card
 * (title/description + Run + outputs); set `showSource: true` in the manifest
 * to reveal the source in the rendered page as well.
 */
const meta = computed<{ title?: string; description?: string; showSource: boolean }>(() => {
  try {
    const parsed = jsyaml.load(yaml.value);
    if (parsed && typeof parsed === 'object') {
      const p = parsed as Record<string, unknown>;
      return {
        title: typeof p.title === 'string' ? p.title : undefined,
        description: typeof p.description === 'string' ? p.description : undefined,
        showSource: p.showSource === true,
      };
    }
  } catch {
    // Invalid YAML mid-edit — no meta.
  }
  return { showSource: false };
});

const editable = ref(props.editor.isEditable);
function syncEditable() { editable.value = props.editor.isEditable; }

const running = ref(false);
const error = ref<string | null>(null);
const result = ref<ComposeRunResult | null>(null);
const progress = ref<ComposeRunResult | null>(null);
/** Server runId of the in-flight run (single or current batch step) — for cancel. */
const runId = ref<string | null>(null);
const cancelling = ref(false);
/** Batch abort flag (Run All Until) — checked between steps. */
const aborted = ref(false);
const menuOpen = ref(false);
/** Non-null while "Run All Until" iterates — its "(i/n)" progress label. */
const batchStatus = ref<string | null>(null);

let polling = false;
let timer: ReturnType<typeof setTimeout> | undefined;

/** Outputs a prior run recorded in `$output:` — shown when no fresh result. */
const persisted = computed<ComposeOutputView[]>(() => readComposeOutputs(yaml.value));

/** The edit-mode textarea, auto-grown to fit its content (no inner scroller). */
const srcEl = ref<HTMLTextAreaElement | null>(null);

/** Resize the textarea to its content height so the cell grows downward. */
function autoGrow() {
  const el = srcEl.value;
  if (!el) return;
  el.style.height = 'auto';
  el.style.height = `${el.scrollHeight}px`;
}

function onYaml(e: Event) {
  props.updateAttributes({ yaml: (e.target as HTMLTextAreaElement).value });
  autoGrow();
}

// Re-fit when the source changes externally or the textarea (re)appears
// (e.g. switching into design mode) — after the DOM has updated.
watch([yaml, editable], () => nextTick(autoGrow));

function stopTimer() {
  polling = false;
  if (timer) clearTimeout(timer);
  timer = undefined;
}

function finishWith(res: ComposeRunResult) {
  stopTimer();
  running.value = false;
  cancelling.value = false;
  runId.value = null;
  progress.value = null;
  result.value = res;
  const outputs = (res.tasks ?? []).flatMap((t) =>
    (t.outputs ?? []).map((o) => ({ path: o.path, uri: o.uri, kind: o.kind, title: o.title })),
  );
  // Success → persist $output; failure → clear the parked $run marker.
  props.updateAttributes({
    yaml: res.success ? writeComposeOutputs(yaml.value, outputs) : clearComposeManaged(yaml.value),
  });
}

function startPolling(runId: string) {
  const poll = props.extension.options.pollCompose;
  if (!poll) return; // no host poll surface — leave it running, nothing to do
  polling = true;
  const tick = async () => {
    if (!polling) return;
    try {
      const res = await poll(runId);
      if (res.running) {
        progress.value = res;
        timer = setTimeout(tick, POLL_MS);
      } else {
        finishWith(res);
      }
    } catch {
      stopTimer();
      running.value = false;
      progress.value = null;
      error.value = 'Lauf nicht mehr verfügbar (Pod-Neustart?) — bitte neu ausführen.';
      props.updateAttributes({ yaml: clearComposeManaged(yaml.value) });
    }
  };
  timer = setTimeout(tick, POLL_MS);
}

async function run() {
  const runner = props.extension.options.runCompose;
  if (!runner || running.value) return;
  stopTimer();
  running.value = true;
  error.value = null;
  result.value = null;
  progress.value = null;
  try {
    const res = await runner(yaml.value);
    if (res.running && res.runId) {
      runId.value = res.runId;
      progress.value = res;
      props.updateAttributes({
        yaml: writeComposeRun(yaml.value, { id: res.runId, startedAt: new Date().toISOString() }),
      });
      startPolling(res.runId);
    } else {
      finishWith(res);
    }
  } catch (e) {
    running.value = false;
    error.value = e instanceof Error ? e.message : 'Compose run failed';
  }
}

/** ■ Stop: cancel the in-flight run on the server (or abort a batch). */
async function stop() {
  aborted.value = true; // breaks the Run-All-Until loop between steps
  const rid = runId.value;
  const cancel = props.extension.options.cancelCompose;
  if (rid && cancel) {
    cancelling.value = true;
    try {
      const res = await cancel(rid);
      if (!res.running) finishWith(res); // else the poll loop observes it shortly
    } catch {
      // Best-effort — the poll loop / batch guard still winds things down.
    } finally {
      cancelling.value = false;
    }
    return;
  }
  // No server run to cancel (e.g. still starting) — detach the UI and drop $run.
  if (!batchStatus.value) {
    stopTimer();
    running.value = false;
    progress.value = null;
    props.updateAttributes({ yaml: clearComposeManaged(yaml.value) });
  }
}

/**
 * Run button has three states: ▶ idle → run; "…" busy (phase 1, the start
 * REST is in flight, no runId yet — nothing to cancel) → disabled; ■ stop
 * (phase 2, we have a runId and can cancel) → stop.
 */
const canStop = computed(() => running.value && runId.value != null);
const runGlyph = computed(() => (!running.value ? '▶' : runId.value ? '■' : '…'));

function onRunButton() {
  if (!running.value) run();
  else if (canStop.value) stop();
}

function toggleMenu() { menuOpen.value = !menuOpen.value; }
function closeMenu() { menuOpen.value = false; }

/** Drop the shown outputs (managed `$output:`/`$run:` block) and reset state. */
function clearOutput() {
  closeMenu();
  stopTimer();
  running.value = false;
  progress.value = null;
  result.value = null;
  error.value = null;
  runId.value = null;
  props.updateAttributes({ yaml: clearComposeManaged(yaml.value) });
}

/** Drop the outputs of every compose block on the page (mirror of Run All Until). */
function clearAllOutput() {
  closeMenu();
  stopTimer();
  running.value = false;
  progress.value = null;
  result.value = null;
  error.value = null;
  runId.value = null;
  const positions: number[] = [];
  props.editor.state.doc.descendants((n, pos) => {
    if (n.type.name === 'vanceCompose') positions.push(pos);
    return true;
  });
  // Attr-only edits don't shift positions, so one command clears them all.
  props.editor.commands.command(({ tr }) => {
    for (const pos of positions) {
      const node = tr.doc.nodeAt(pos);
      if (!node) continue;
      tr.setNodeAttribute(pos, 'yaml', clearComposeManaged((node.attrs?.yaml as string) ?? ''));
    }
    return true;
  });
}

/** UI-only manifest flag `autoRun: false` opts a block out of "Run All Until". */
function isAutoRunDisabled(src: string): boolean {
  try {
    const parsed = jsyaml.load(src);
    return !!parsed && typeof parsed === 'object'
      && (parsed as Record<string, unknown>).autoRun === false;
  } catch {
    return false;
  }
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Poll a run to a terminal state (used by the batch, which awaits each step). */
async function pollUntilDone(rid: string): Promise<ComposeRunResult> {
  const poll = props.extension.options.pollCompose;
  if (!poll) return { running: false, success: false, error: 'no poll surface' };
  for (;;) {
    if (aborted.value) return { running: false, success: false, error: 'cancelled' };
    await delay(POLL_MS);
    const res = await poll(rid);
    if (!res.running) return res;
  }
}

/** Run one block's YAML to completion (awaits async runs), tracking its runId. */
async function executeToCompletion(
  runner: (yaml: string) => Promise<ComposeRunResult>,
  src: string,
): Promise<ComposeRunResult> {
  const res = await runner(src);
  if (res.running && res.runId) {
    runId.value = res.runId;
    const done = await pollUntilDone(res.runId);
    runId.value = null;
    return done;
  }
  return res;
}

/** Write a completed run's outputs back into the block at `pos`. */
function persistBlockResult(pos: number, src: string, res: ComposeRunResult) {
  const outputs = (res.tasks ?? []).flatMap((t) =>
    (t.outputs ?? []).map((o) => ({ path: o.path, uri: o.uri, kind: o.kind, title: o.title })),
  );
  const newYaml = res.success ? writeComposeOutputs(src, outputs) : clearComposeManaged(src);
  if (pos === props.getPos()) {
    props.updateAttributes({ yaml: newYaml });
  } else {
    props.editor.commands.command(({ tr }) => {
      tr.setNodeAttribute(pos, 'yaml', newYaml);
      return true;
    });
  }
}

/**
 * "Run All Until": run every compose block on the page from the top through
 * this one, in document order, sequentially (each awaits the previous). Blocks
 * with `autoRun: false` are skipped; the chain halts on the first failure.
 */
async function runAllUntil() {
  closeMenu();
  const runner = props.extension.options.runCompose;
  const here = props.getPos();
  if (!runner || running.value || here === undefined) return;

  const blocks: { pos: number; yaml: string }[] = [];
  props.editor.state.doc.descendants((n, pos) => {
    if (n.type.name === 'vanceCompose' && pos <= here) {
      const src = (n.attrs?.yaml as string | undefined) ?? '';
      if (!isAutoRunDisabled(src)) blocks.push({ pos, yaml: src });
    }
    return true;
  });
  if (!blocks.length) return;

  aborted.value = false;
  running.value = true;
  error.value = null;
  result.value = null;
  progress.value = null;
  try {
    for (let i = 0; i < blocks.length; i++) {
      if (aborted.value) break;
      batchStatus.value = `Run All Until… (${i + 1}/${blocks.length})`;
      const b = blocks[i];
      const res = await executeToCompletion(runner, b.yaml);
      persistBlockResult(b.pos, b.yaml, res);
      if (!res.success) {
        error.value = res.error ?? `Block bei ${b.pos} fehlgeschlagen`;
        break;
      }
    }
  } finally {
    running.value = false;
    batchStatus.value = null;
    runId.value = null;
  }
}

onMounted(() => {
  props.editor.on('update', syncEditable);
  props.editor.on('transaction', syncEditable);
  nextTick(autoGrow);
  window.addEventListener('click', closeMenu);
  // Resume a run that was in flight before a reload.
  const marker = readComposeRun(yaml.value);
  if (marker) {
    running.value = true;
    runId.value = marker.id;
    startPolling(marker.id);
  }
});
onBeforeUnmount(() => {
  props.editor.off('update', syncEditable);
  props.editor.off('transaction', syncEditable);
  window.removeEventListener('click', closeMenu);
  stopTimer();
});

</script>

<template>
  <NodeViewWrapper as="aside" class="vance-compose">
    <div class="vance-compose__cell" contenteditable="false">
      <div v-if="meta.title || meta.description" class="vance-compose__meta">
        <div v-if="meta.title" class="vance-compose__title">{{ meta.title }}</div>
        <div v-if="meta.description" class="vance-compose__desc">{{ meta.description }}</div>
      </div>
      <textarea
        v-if="editable"
        ref="srcEl"
        class="vance-compose__src"
        :value="yaml"
        rows="1"
        spellcheck="false"
        placeholder="workspace: { name: my-workspace, type: temp }  ·  tasks: [ … ]"
        @input="onYaml"
        @mousedown.stop
        @keydown.stop
      ></textarea>
      <pre v-else-if="meta.showSource" class="vance-compose__src vance-compose__src--ro">{{ yaml }}</pre>

      <div class="vance-compose__bar">
        <button
          type="button"
          class="vance-compose__run"
          :class="{ 'vance-compose__run--stop': canStop }"
          :disabled="cancelling || (running && !canStop)"
          :title="!running ? 'Run compose' : (canStop ? 'Stop' : 'Läuft…')"
          @click.stop="onRunButton"
        >{{ runGlyph }}</button>

        <div class="vance-compose__menu-wrap">
          <button
            type="button"
            class="vance-compose__menu-btn"
            title="Weitere Aktionen"
            @click.stop="toggleMenu"
          >…</button>
          <div v-if="menuOpen" class="vance-compose__menu" @click.stop>
            <button
              type="button"
              class="vance-compose__menu-item"
              :disabled="running"
              @click="runAllUntil"
            >Run All Until</button>
            <button
              type="button"
              class="vance-compose__menu-item"
              @click="clearOutput"
            >Clear Output</button>
            <button
              type="button"
              class="vance-compose__menu-item"
              @click="clearAllOutput"
            >Clear All Output</button>
          </div>
        </div>

        <span v-if="batchStatus" class="vance-compose__status">{{ batchStatus }}</span>
        <span v-else-if="cancelling" class="vance-compose__status">stoppe…</span>
        <span v-else-if="result" class="vance-compose__status">
          {{ result.workspace ? result.workspace + ' · ' : '' }}{{ result.success ? 'success' : 'failed' }}
        </span>
        <span v-else-if="progress" class="vance-compose__status">
          läuft… Task {{ (progress.currentTaskIndex ?? 0) + 1 }}{{ progress.currentTaskType ? ` (${progress.currentTaskType})` : '' }}
        </span>
      </div>

      <div v-if="error" class="vance-compose__error">{{ error }}</div>
      <div
        v-else-if="result && !result.success && result.error"
        class="vance-compose__error"
      >{{ result.error }}</div>

      <pre
        v-if="progress"
        class="vance-compose__log"
      >{{ progress.tail && progress.tail.length ? progress.tail.join('\n') : '… läuft, warte auf Ausgabe' }}</pre>

      <div v-if="result" class="vance-compose__out">
        <template v-for="(task, ti) in result.tasks ?? []" :key="ti">
          <div
            v-if="task.status !== 'success' && task.error"
            class="vance-compose__error"
          >Task {{ ti + 1 }}: {{ task.error }}</div>
          <template v-for="(o, oi) in (task.outputs ?? [])" :key="oi">
            <component
              :is="outputComponent"
              v-if="outputComponent"
              :project-id="projectId"
              :output="o"
            />
            <div v-else class="vance-compose__art">
              <div class="vance-compose__art-title">{{ o.title || o.path }}</div>
              <div class="vance-compose__desc">{{ o.path }}</div>
            </div>
          </template>
          <pre
            v-if="task.log && (task.outputs?.length ?? 0) === 0"
            class="vance-compose__log"
          >{{ task.log }}</pre>
        </template>
      </div>

      <div v-else-if="!progress && persisted.length" class="vance-compose__out">
        <template v-for="(o, oi) in persisted" :key="oi">
          <component
            :is="outputComponent"
            v-if="outputComponent"
            :project-id="projectId"
            :output="o"
          />
          <div v-else class="vance-compose__art">
            <div class="vance-compose__art-title">{{ o.title || o.path }}</div>
            <div class="vance-compose__desc">{{ o.path }}</div>
          </div>
        </template>
      </div>
    </div>
  </NodeViewWrapper>
</template>

<style scoped>
.vance-compose {
  margin: 0.6em 0;
}
.vance-compose__cell {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  border: 1px solid oklch(var(--bc) / 0.15);
  border-radius: 0.5rem;
  padding: 0.6rem 0.75rem;
  background: oklch(var(--bc) / 0.03);
}
.vance-compose__meta {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
}
.vance-compose__title { font-weight: 600; }
.vance-compose__desc {
  font-size: 0.82rem;
  opacity: 0.7;
  white-space: pre-line;
}
.vance-compose__src {
  width: 100%;
  box-sizing: border-box;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.82rem;
  line-height: 1.4;
  border: 1px solid oklch(var(--bc) / 0.2);
  border-radius: 0.35rem;
  padding: 0.5rem 0.6rem;
  background: oklch(var(--b1));
  color: inherit;
  resize: none;
  white-space: pre;
  /* Height is driven by autoGrow() to fit the content — no vertical scroller;
     long lines still scroll horizontally. */
  overflow-x: auto;
  overflow-y: hidden;
  min-height: 2.5rem;
}
.vance-compose__src--ro {
  margin: 0;
}
.vance-compose__bar {
  display: flex;
  align-items: center;
  gap: 0.4rem;
}
.vance-compose__run {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 2rem;
  height: 2rem;
  border: 1px solid oklch(var(--p));
  background: oklch(var(--p));
  color: oklch(var(--pc));
  border-radius: 0.35rem;
  font-size: 0.9rem;
  line-height: 1;
  cursor: pointer;
}
.vance-compose__run--stop {
  border-color: oklch(var(--er));
  background: oklch(var(--er));
  color: oklch(var(--erc, var(--pc)));
}
.vance-compose__run:disabled { opacity: 0.55; cursor: default; }
.vance-compose__menu-wrap { position: relative; }
.vance-compose__menu-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 2rem;
  height: 2rem;
  border: 1px solid oklch(var(--bc) / 0.25);
  background: transparent;
  color: inherit;
  border-radius: 0.35rem;
  font-size: 1.1rem;
  line-height: 1;
  cursor: pointer;
}
.vance-compose__menu-btn:hover { background: oklch(var(--bc) / 0.08); }
.vance-compose__menu {
  position: absolute;
  top: calc(100% + 0.25rem);
  left: 0;
  z-index: 20;
  display: flex;
  flex-direction: column;
  min-width: 11rem;
  padding: 0.25rem;
  border: 1px solid oklch(var(--bc) / 0.2);
  border-radius: 0.4rem;
  background: oklch(var(--b1));
  box-shadow: 0 6px 20px oklch(0% 0 0 / 0.18);
}
.vance-compose__menu-item {
  text-align: left;
  border: none;
  background: transparent;
  color: inherit;
  border-radius: 0.3rem;
  padding: 0.4rem 0.6rem;
  font-size: 0.85rem;
  cursor: pointer;
}
.vance-compose__menu-item:hover:not(:disabled) { background: oklch(var(--bc) / 0.1); }
.vance-compose__menu-item:disabled { opacity: 0.45; cursor: default; }
.vance-compose__status { font-size: 0.8rem; opacity: 0.7; }
.vance-compose__out {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.vance-compose__art {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.vance-compose__art-title { font-size: 0.78rem; opacity: 0.7; }
.vance-compose__img { max-width: 100%; border-radius: 0.35rem; }
.vance-compose__link { font-size: 0.85rem; }
.vance-compose__log {
  margin: 0;
  font-size: 0.78rem;
  white-space: pre-wrap;
  overflow: auto;
  opacity: 0.85;
}
.vance-compose__error {
  color: oklch(var(--er));
  font-size: 0.82rem;
}
</style>

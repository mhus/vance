<script setup lang="ts">
/**
 * NodeView for the {@code vance-compose} block — an inline Damogran compose
 * task cell.
 *
 * - **design mode**: an editable YAML textarea (the compose manifest) plus a
 *   "Run compose" button; edits are written back to the block's `yaml` attr.
 * - **work mode** (read-only page): the YAML shown read-only + the run button.
 *
 * Running calls the host `runCompose` with the current YAML and renders the
 * returned per-task outputs (images inline, everything else as a link). The
 * host resolves output content URLs, so this view needs no tenant/REST access.
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
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
  composeOutputComponent?: (() => Component | null) | null;
  projectId?: string;
}

const props = defineProps<{
  node: ProseMirrorNode;
  updateAttributes: (attrs: Record<string, unknown>) => void;
  editor: Editor;
  extension: { options: ExtensionOptions };
}>();

/** Host-injected output renderer (vance-face ComposeOutput) + its project id. */
const outputComponent = computed<Component | null>(
  () => props.extension.options.composeOutputComponent?.() ?? null,
);
const projectId = computed<string>(() => props.extension.options.projectId ?? '');

const yaml = computed(() => (props.node.attrs?.yaml as string | null) ?? '');

/** Optional title/description from the manifest, shown above the cell. */
const meta = computed<{ title?: string; description?: string }>(() => {
  try {
    const parsed = jsyaml.load(yaml.value);
    if (parsed && typeof parsed === 'object') {
      const p = parsed as Record<string, unknown>;
      return {
        title: typeof p.title === 'string' ? p.title : undefined,
        description: typeof p.description === 'string' ? p.description : undefined,
      };
    }
  } catch {
    // Invalid YAML mid-edit — no meta.
  }
  return {};
});

const editable = ref(props.editor.isEditable);
function syncEditable() { editable.value = props.editor.isEditable; }

const running = ref(false);
const error = ref<string | null>(null);
const result = ref<ComposeRunResult | null>(null);
const progress = ref<ComposeRunResult | null>(null);

let polling = false;
let timer: ReturnType<typeof setTimeout> | undefined;

/** Outputs a prior run recorded in `$output:` — shown when no fresh result. */
const persisted = computed<ComposeOutputView[]>(() => readComposeOutputs(yaml.value));

function onYaml(e: Event) {
  props.updateAttributes({ yaml: (e.target as HTMLTextAreaElement).value });
}

function stopTimer() {
  polling = false;
  if (timer) clearTimeout(timer);
  timer = undefined;
}

function finishWith(res: ComposeRunResult) {
  stopTimer();
  running.value = false;
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

onMounted(() => {
  props.editor.on('update', syncEditable);
  props.editor.on('transaction', syncEditable);
  // Resume a run that was in flight before a reload.
  const marker = readComposeRun(yaml.value);
  if (marker) {
    running.value = true;
    startPolling(marker.id);
  }
});
onBeforeUnmount(() => {
  props.editor.off('update', syncEditable);
  props.editor.off('transaction', syncEditable);
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
        class="vance-compose__src"
        :value="yaml"
        rows="8"
        spellcheck="false"
        placeholder="workspace: { name: my-workspace, type: temp }  ·  tasks: [ … ]"
        @input="onYaml"
        @mousedown.stop
        @keydown.stop
      ></textarea>
      <pre v-else class="vance-compose__src vance-compose__src--ro">{{ yaml }}</pre>

      <div class="vance-compose__bar">
        <button
          type="button"
          class="vance-compose__btn"
          :disabled="running"
          @click="run"
        >{{ running ? '…' : '▶ Run compose' }}</button>
        <span v-if="result" class="vance-compose__status">
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
        v-if="progress && progress.tail && progress.tail.length"
        class="vance-compose__log"
      >{{ progress.tail.join('\n') }}</pre>

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
  resize: vertical;
  white-space: pre;
  overflow: auto;
}
.vance-compose__src--ro {
  margin: 0;
}
.vance-compose__bar {
  display: flex;
  align-items: center;
  gap: 0.6rem;
}
.vance-compose__btn {
  border: 1px solid oklch(var(--p));
  background: oklch(var(--p));
  color: oklch(var(--pc));
  border-radius: 0.35rem;
  padding: 0.35rem 0.9rem;
  font-size: 0.88rem;
  font-weight: 500;
  cursor: pointer;
}
.vance-compose__btn:disabled { opacity: 0.55; cursor: default; }
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

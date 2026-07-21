<script setup lang="ts">
/**
 * NodeView for the `vance-compose-<lang>` script blocks (js / bash / python).
 *
 * Same run engine as `vance-compose` (via {@link useComposeRun}), but the
 * manifest is constrained to exactly ONE task of a fixed type. Two editors: a
 * dedicated **script** pane (the task's code/command) plus the **settings YAML**
 * (title/description/workspace/unknown — still freely editable). Editing the
 * script re-serialises the manifest with the task normalised to the fixed type;
 * editing the YAML re-normalises on blur (a stray/extra task is overwritten).
 *
 * Host callbacks (run/poll/cancel + output renderer + projectId) come via
 * inject (`vance:compose-host`, provided by WorkPageEditor) — the block is a
 * globally-registered node and cannot take per-instance extension options.
 */
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import jsyaml from 'js-yaml';
import { NodeViewWrapper } from '@tiptap/vue-3';
import type { Editor } from '@tiptap/core';
import type { Component } from 'vue';
import type { Node as ProseMirrorNode } from '@tiptap/pm/model';
import { useComposeRun, type ComposeHost } from '../composables/useComposeRun';
import { kindByNodeName, extractScript, applyScript, normalizeManifest } from '../builtins/scriptComposeCodec';

const props = defineProps<{
  node: ProseMirrorNode;
  updateAttributes: (attrs: Record<string, unknown>) => void;
  editor: Editor;
}>();

const kind = computed(() => kindByNodeName(props.node.type.name));
const yaml = computed<string>(() => (props.node.attrs?.yaml as string | null) ?? '');
function setYaml(y: string) { props.updateAttributes({ yaml: y }); }

/** Host compose surface (provided by WorkPageEditor); inert-but-safe fallback. */
interface ComposeHostInjection extends ComposeHost {
  composeOutputComponent: () => Component | null;
  projectId: () => string;
}
const host = inject<ComposeHostInjection | null>('vance:compose-host', null);
const unavailable = (msg: string) => async () => ({ running: false, success: false, tasks: [], error: msg });
const composeHost: ComposeHost = {
  runCompose: host?.runCompose ?? unavailable('Compose run not available in this context.'),
  pollCompose: host?.pollCompose ?? unavailable('Compose polling not available.'),
  cancelCompose: host?.cancelCompose ?? unavailable('Compose cancel not available.'),
};
const outputComponent = computed<Component | null>(() => host?.composeOutputComponent?.() ?? null);
const projectId = computed<string>(() => host?.projectId?.() ?? '');

const rc = useComposeRun({ yaml: () => yaml.value, setYaml, host: composeHost });

/** The single task's script (derived from the manifest). */
const script = computed<string>(() => (kind.value ? extractScript(yaml.value, kind.value.scriptField) : ''));

function onScript(e: Event) {
  const k = kind.value;
  if (!k) return;
  setYaml(applyScript(yaml.value, k, (e.target as HTMLTextAreaElement).value));
}
function onYaml(e: Event) { setYaml((e.target as HTMLTextAreaElement).value); }
function onYamlBlur() {
  const k = kind.value;
  if (k) setYaml(normalizeManifest(yaml.value, k));
}

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
  } catch { /* invalid mid-edit */ }
  return {};
});

const editable = ref(props.editor.isEditable);
function syncEditable() { editable.value = props.editor.isEditable; }

const scriptEl = ref<HTMLTextAreaElement | null>(null);
const yamlEl = ref<HTMLTextAreaElement | null>(null);
function grow(el: HTMLTextAreaElement | null) {
  if (!el) return;
  el.style.height = 'auto';
  el.style.height = `${el.scrollHeight}px`;
}
function growAll() { grow(scriptEl.value); grow(yamlEl.value); }
watch([script, yaml, editable], () => nextTick(growAll));

const menuOpen = ref(false);
function toggleMenu() { menuOpen.value = !menuOpen.value; }
function closeMenu() { menuOpen.value = false; }
function doClearOutput() { closeMenu(); rc.clearOutput(); }

onMounted(() => {
  props.editor.on('update', syncEditable);
  props.editor.on('transaction', syncEditable);
  nextTick(growAll);
  window.addEventListener('click', closeMenu);
  rc.resumeIfInFlight();
});
onBeforeUnmount(() => {
  props.editor.off('update', syncEditable);
  props.editor.off('transaction', syncEditable);
  window.removeEventListener('click', closeMenu);
  rc.teardown();
});
</script>

<template>
  <NodeViewWrapper as="aside" class="vance-compose">
    <div class="vance-compose__cell" contenteditable="false">
      <div v-if="meta.title || meta.description" class="vance-compose__meta">
        <div v-if="meta.title" class="vance-compose__title">{{ meta.title }}</div>
        <div v-if="meta.description" class="vance-compose__desc">{{ meta.description }}</div>
      </div>

      <!-- Edit mode: script pane + settings-YAML pane side by side. -->
      <div v-if="editable" class="vance-compose__panes">
        <label class="vance-compose__pane vance-compose__pane--script">
          <span class="vance-compose__pane-label">{{ kind?.label ?? 'Script' }}</span>
          <textarea
            ref="scriptEl"
            class="vance-compose__src"
            :value="script"
            rows="1"
            spellcheck="false"
            :placeholder="kind?.placeholder"
            @input="onScript"
            @mousedown.stop
            @keydown.stop
          ></textarea>
        </label>
        <label class="vance-compose__pane vance-compose__pane--yaml">
          <span class="vance-compose__pane-label">Einstellungen (YAML)</span>
          <textarea
            ref="yamlEl"
            class="vance-compose__src"
            :value="yaml"
            rows="1"
            spellcheck="false"
            @input="onYaml"
            @blur="onYamlBlur"
            @mousedown.stop
            @keydown.stop
          ></textarea>
        </label>
      </div>

      <!-- Work mode: read-only script. -->
      <pre v-else class="vance-compose__src vance-compose__src--ro">{{ script }}</pre>

      <div class="vance-compose__bar">
        <button
          type="button"
          class="vance-compose__run"
          :class="{ 'vance-compose__run--stop': rc.canStop.value }"
          :disabled="rc.cancelling.value || (rc.running.value && !rc.canStop.value)"
          :title="!rc.running.value ? 'Run' : (rc.canStop.value ? 'Stop' : 'Läuft…')"
          @click.stop="rc.onRunButton()"
        >{{ rc.runGlyph.value }}</button>

        <div class="vance-compose__menu-wrap">
          <button
            type="button"
            class="vance-compose__menu-btn"
            title="Weitere Aktionen"
            @click.stop="toggleMenu"
          >…</button>
          <div v-if="menuOpen" class="vance-compose__menu" @click.stop>
            <button type="button" class="vance-compose__menu-item" @click="doClearOutput">Clear Output</button>
          </div>
        </div>

        <span v-if="rc.cancelling.value" class="vance-compose__status">stoppe…</span>
        <span v-else-if="rc.result.value" class="vance-compose__status">
          {{ rc.result.value.workspace ? rc.result.value.workspace + ' · ' : '' }}{{ rc.result.value.success ? 'success' : 'failed' }}
        </span>
        <span v-else-if="rc.progress.value" class="vance-compose__status">
          läuft… Task {{ (rc.progress.value.currentTaskIndex ?? 0) + 1 }}
        </span>
      </div>

      <div v-if="rc.error.value" class="vance-compose__error">{{ rc.error.value }}</div>
      <div
        v-else-if="rc.result.value && !rc.result.value.success && rc.result.value.error"
        class="vance-compose__error"
      >{{ rc.result.value.error }}</div>

      <pre
        v-if="rc.progress.value"
        class="vance-compose__log"
      >{{ rc.progress.value.tail && rc.progress.value.tail.length ? rc.progress.value.tail.join('\n') : '… läuft, warte auf Ausgabe' }}</pre>

      <!-- Fixed `output:` override wins over run/persisted outputs. -->
      <div v-if="rc.fixedOutputs.value.length" class="vance-compose__out">
        <template v-for="(o, oi) in rc.fixedOutputs.value" :key="oi">
          <component :is="outputComponent" v-if="outputComponent" :project-id="projectId" :output="o" />
          <div v-else class="vance-compose__art">
            <div class="vance-compose__art-title">{{ o.title || o.path }}</div>
            <div class="vance-compose__desc">{{ o.path }}</div>
          </div>
        </template>
      </div>

      <div v-else-if="rc.result.value" class="vance-compose__out">
        <template v-for="(task, ti) in rc.result.value.tasks ?? []" :key="ti">
          <div v-if="task.status !== 'success' && task.error" class="vance-compose__error">
            Task {{ ti + 1 }}: {{ task.error }}
          </div>
          <template v-for="(o, oi) in (task.outputs ?? [])" :key="oi">
            <component :is="outputComponent" v-if="outputComponent" :project-id="projectId" :output="o" />
            <div v-else class="vance-compose__art">
              <div class="vance-compose__art-title">{{ o.title || o.path }}</div>
              <div class="vance-compose__desc">{{ o.path }}</div>
            </div>
          </template>
          <pre v-if="task.log && (task.outputs?.length ?? 0) === 0" class="vance-compose__log">{{ task.log }}</pre>
        </template>
      </div>

      <div v-else-if="!rc.progress.value && rc.persisted.value.length" class="vance-compose__out">
        <template v-for="(o, oi) in rc.persisted.value" :key="oi">
          <component :is="outputComponent" v-if="outputComponent" :project-id="projectId" :output="o" />
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
.vance-compose { margin: 0.6em 0; }
.vance-compose__cell {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  border: 1px solid oklch(var(--bc) / 0.15);
  border-radius: 0.5rem;
  padding: 0.6rem 0.75rem;
  background: oklch(var(--bc) / 0.03);
}
.vance-compose__meta { display: flex; flex-direction: column; gap: 0.15rem; }
.vance-compose__title { font-weight: 600; }
.vance-compose__desc { font-size: 0.82rem; opacity: 0.7; white-space: pre-line; }
.vance-compose__panes { display: flex; gap: 0.6rem; flex-wrap: wrap; }
.vance-compose__pane { display: flex; flex-direction: column; gap: 0.2rem; min-width: 0; }
.vance-compose__pane--script { flex: 2 1 20rem; }
.vance-compose__pane--yaml { flex: 1 1 14rem; }
.vance-compose__pane-label { font-size: 0.72rem; text-transform: uppercase; letter-spacing: 0.03em; opacity: 0.55; }
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
  overflow-x: auto;
  overflow-y: hidden;
  min-height: 2.5rem;
}
.vance-compose__src--ro { margin: 0; }
.vance-compose__bar { display: flex; align-items: center; gap: 0.4rem; }
.vance-compose__run {
  display: inline-flex; align-items: center; justify-content: center;
  width: 2rem; height: 2rem;
  border: 1px solid oklch(var(--p)); background: oklch(var(--p)); color: oklch(var(--pc));
  border-radius: 0.35rem; font-size: 0.9rem; line-height: 1; cursor: pointer;
}
.vance-compose__run--stop { border-color: oklch(var(--er)); background: oklch(var(--er)); color: oklch(var(--erc, var(--pc))); }
.vance-compose__run:disabled { opacity: 0.55; cursor: default; }
.vance-compose__menu-wrap { position: relative; }
.vance-compose__menu-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 2rem; height: 2rem;
  border: 1px solid oklch(var(--bc) / 0.25); background: transparent; color: inherit;
  border-radius: 0.35rem; font-size: 1.1rem; line-height: 1; cursor: pointer;
}
.vance-compose__menu-btn:hover { background: oklch(var(--bc) / 0.08); }
.vance-compose__menu {
  position: absolute; top: calc(100% + 0.25rem); left: 0; z-index: 20;
  display: flex; flex-direction: column; min-width: 11rem; padding: 0.25rem;
  border: 1px solid oklch(var(--bc) / 0.2); border-radius: 0.4rem; background: oklch(var(--b1));
  box-shadow: 0 6px 20px oklch(0% 0 0 / 0.18);
}
.vance-compose__menu-item {
  text-align: left; border: none; background: transparent; color: inherit;
  border-radius: 0.3rem; padding: 0.4rem 0.6rem; font-size: 0.85rem; cursor: pointer;
}
.vance-compose__menu-item:hover { background: oklch(var(--bc) / 0.1); }
.vance-compose__status { font-size: 0.8rem; opacity: 0.7; }
.vance-compose__out { display: flex; flex-direction: column; gap: 0.5rem; }
.vance-compose__art { display: flex; flex-direction: column; gap: 0.25rem; }
.vance-compose__art-title { font-size: 0.78rem; opacity: 0.7; }
.vance-compose__log { margin: 0; font-size: 0.78rem; white-space: pre-wrap; overflow: auto; opacity: 0.85; }
.vance-compose__error { color: oklch(var(--er)); font-size: 0.82rem; }
</style>

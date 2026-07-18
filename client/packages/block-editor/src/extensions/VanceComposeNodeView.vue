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
import { NodeViewWrapper } from '@tiptap/vue-3';
import type { Editor } from '@tiptap/core';
import type { Node as ProseMirrorNode } from '@tiptap/pm/model';
import type { ComposeOutputView, ComposeRunResult } from './VanceCompose';

interface ExtensionOptions {
  runCompose?: ((yaml: string) => Promise<ComposeRunResult>) | null;
}

const props = defineProps<{
  node: ProseMirrorNode;
  updateAttributes: (attrs: Record<string, unknown>) => void;
  editor: Editor;
  extension: { options: ExtensionOptions };
}>();

const yaml = computed(() => (props.node.attrs?.yaml as string | null) ?? '');

const editable = ref(props.editor.isEditable);
function syncEditable() { editable.value = props.editor.isEditable; }
onMounted(() => {
  props.editor.on('update', syncEditable);
  props.editor.on('transaction', syncEditable);
});
onBeforeUnmount(() => {
  props.editor.off('update', syncEditable);
  props.editor.off('transaction', syncEditable);
});

const running = ref(false);
const error = ref<string | null>(null);
const result = ref<ComposeRunResult | null>(null);

function onYaml(e: Event) {
  props.updateAttributes({ yaml: (e.target as HTMLTextAreaElement).value });
}

async function run() {
  const runner = props.extension.options.runCompose;
  if (!runner || running.value) return;
  running.value = true;
  error.value = null;
  result.value = null;
  try {
    result.value = await runner(yaml.value);
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Compose run failed';
  } finally {
    running.value = false;
  }
}

function isImage(o: ComposeOutputView): boolean {
  return o.kind === 'image' || o.kind === 'svg';
}
</script>

<template>
  <NodeViewWrapper as="aside" class="vance-compose">
    <div class="vance-compose__cell" contenteditable="false">
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
      </div>

      <div v-if="error" class="vance-compose__error">{{ error }}</div>
      <div
        v-else-if="result && !result.success && result.error"
        class="vance-compose__error"
      >{{ result.error }}</div>

      <div v-if="result" class="vance-compose__out">
        <template v-for="(task, ti) in result.tasks" :key="ti">
          <div
            v-if="task.status !== 'success' && task.error"
            class="vance-compose__error"
          >Task {{ ti + 1 }}: {{ task.error }}</div>
          <div v-for="(o, oi) in (task.outputs ?? [])" :key="oi" class="vance-compose__art">
            <div class="vance-compose__art-title">{{ o.title || o.path }}</div>
            <img v-if="isImage(o)" :src="o.href" :alt="o.path" class="vance-compose__img" />
            <a v-else :href="o.href" target="_blank" rel="noopener" class="vance-compose__link">
              {{ o.path }}
            </a>
          </div>
          <pre
            v-if="task.log && (task.outputs?.length ?? 0) === 0"
            class="vance-compose__log"
          >{{ task.log }}</pre>
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

<script setup lang="ts">
/**
 * NodeView for the {@code vance-button} block.
 *
 * - **work mode** (read-only page): a clickable button (label = `title`);
 *   click runs the `script` via the host `runScript` callback.
 * - **design mode**: inputs for title / script (+ type; v1 is `script`
 *   only), written back to the block attributes.
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { NodeViewWrapper } from '@tiptap/vue-3';
import type { Editor } from '@tiptap/core';
import type { Node as ProseMirrorNode } from '@tiptap/pm/model';

interface ExtensionOptions {
  runScript?: ((scriptRef: string) => Promise<void>) | null;
}

const props = defineProps<{
  node: ProseMirrorNode;
  updateAttributes: (attrs: Record<string, unknown>) => void;
  editor: Editor;
  extension: { options: ExtensionOptions };
}>();

const type = computed(() => (props.node.attrs?.type as string | null) ?? 'script');
const script = computed(() => (props.node.attrs?.script as string | null) ?? '');
const title = computed(() => (props.node.attrs?.title as string | null) ?? '');

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
const ranAt = ref(false);

async function run() {
  const runner = props.extension.options.runScript;
  if (!runner || !script.value.trim() || running.value) return;
  running.value = true;
  error.value = null;
  ranAt.value = false;
  try {
    await runner(script.value.trim());
    ranAt.value = true;
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Script failed';
  } finally {
    running.value = false;
  }
}

function onTitle(e: Event) {
  props.updateAttributes({ title: (e.target as HTMLInputElement).value });
}
function onScript(e: Event) {
  props.updateAttributes({ script: (e.target as HTMLInputElement).value });
}
</script>

<template>
  <NodeViewWrapper as="aside" class="vance-button">
    <!-- DESIGN: config inputs -->
    <div v-if="editable" class="vance-button__design" contenteditable="false">
      <div class="vance-button__row">
        <select class="vance-button__inp" :value="type" disabled @mousedown.stop>
          <option value="script">script</option>
        </select>
        <input
          class="vance-button__inp"
          style="flex: 1"
          placeholder="Button title…"
          :value="title"
          @input="onTitle"
          @mousedown.stop
          @keydown.stop
        />
      </div>
      <input
        class="vance-button__inp"
        placeholder="script (e.g. myscript.js or vance:/apps/x/run.js)"
        :value="script"
        @input="onScript"
        @mousedown.stop
        @keydown.stop
      />
      <div v-if="error" class="vance-button__error">{{ error }}</div>
    </div>

    <!-- WORK: clickable button -->
    <div v-else class="vance-button__work" contenteditable="false">
      <button
        type="button"
        class="vance-button__btn"
        :disabled="running || !script"
        @click="run"
      >{{ running ? '…' : (title || 'Run') }}</button>
      <span v-if="ranAt" class="vance-button__ok">✓</span>
      <span v-if="error" class="vance-button__error">{{ error }}</span>
    </div>
  </NodeViewWrapper>
</template>

<style scoped>
.vance-button {
  margin: 0.6em 0;
}
.vance-button__design {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
  border: 1px dashed oklch(var(--bc) / 0.3);
  border-radius: 0.5rem;
  padding: 0.6rem 0.75rem;
  background: oklch(var(--bc) / 0.03);
}
.vance-button__row {
  display: flex;
  gap: 0.4rem;
  align-items: center;
}
.vance-button__inp {
  border: 1px solid oklch(var(--bc) / 0.2);
  border-radius: 0.25rem;
  padding: 0.3rem 0.5rem;
  font: inherit;
  font-size: 0.85rem;
  background: oklch(var(--b1));
  color: inherit;
  box-sizing: border-box;
}
.vance-button__work {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.vance-button__btn {
  border: 1px solid oklch(var(--p));
  background: oklch(var(--p));
  color: oklch(var(--pc));
  border-radius: 0.35rem;
  padding: 0.4rem 1rem;
  font-size: 0.9rem;
  font-weight: 500;
  cursor: pointer;
}
.vance-button__btn:disabled { opacity: 0.55; cursor: default; }
.vance-button__ok { color: oklch(var(--su)); font-size: 0.9rem; }
.vance-button__error {
  color: oklch(var(--er));
  font-size: 0.8rem;
}
</style>

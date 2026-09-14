<script setup lang="ts">
/**
 * NodeView for the {@code vance-button} block.
 *
 * - **work mode** (read-only page): a clickable button (label = `title`);
 *   click runs the button's action (type + script) via the host
 *   `runButton` callback and shows the returned summary message (score,
 *   confirmation) inline.
 * - **design mode**: inputs for type / title / script (`script` applies to
 *   `type: script` only), written back to the block attributes.
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useT } from '../useT';
import { NodeViewWrapper } from '@tiptap/vue-3';
import type { Editor } from '@tiptap/core';
import type { Node as ProseMirrorNode } from '@tiptap/pm/model';

const t = useT();

interface ExtensionOptions {
  runButton?:
    | ((button: { type: string; script: string; title: string }) => Promise<string | null>)
    | null;
}

const props = defineProps<{
  node: ProseMirrorNode;
  updateAttributes: (attrs: Record<string, unknown>) => void;
  editor: Editor;
  extension: { options: ExtensionOptions };
}>();

const BUTTON_TYPES = ['script', 'form-resolve', 'form-reset'] as const;

const type = computed(() =>
  BUTTON_TYPES.includes(props.node.attrs?.type as (typeof BUTTON_TYPES)[number])
    ? (props.node.attrs.type as string)
    : 'script',
);
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
const message = ref<string | null>(null);

async function run() {
  const runner = props.extension.options.runButton;
  if (!runner || running.value) return;
  if (type.value === 'script' && !script.value.trim()) return;
  running.value = true;
  error.value = null;
  message.value = null;
  try {
    message.value = await runner({ type: type.value, script: script.value.trim(), title: title.value });
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Action failed';
  } finally {
    running.value = false;
  }
}

function onType(e: Event) {
  props.updateAttributes({ type: (e.target as HTMLSelectElement).value });
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
        <select class="vance-button__inp" :value="type" @change="onType" @mousedown.stop>
          <option value="script">{{ t('blockEditor.button.typeScript') }}</option>
          <option value="form-resolve">{{ t('blockEditor.button.typeFormResolve') }}</option>
          <option value="form-reset">{{ t('blockEditor.button.typeFormReset') }}</option>
        </select>
        <input
          class="vance-button__inp"
          style="flex: 1"
          :placeholder="t('blockEditor.button.titlePlaceholder')"
          :value="title"
          @input="onTitle"
          @mousedown.stop
          @keydown.stop
        />
      </div>
      <input
        v-if="type === 'script'"
        class="vance-button__inp"
        :placeholder="t('blockEditor.button.scriptPlaceholder')"
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
        :disabled="running || (type === 'script' && !script)"
        @click="run"
      >{{ running ? '…' : (title || t('blockEditor.button.runDefault')) }}</button>
      <span v-if="message" class="vance-button__ok">{{ message }}</span>
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
  border: 1px dashed color-mix(in oklab, var(--color-base-content) 30%, transparent);
  border-radius: 0.5rem;
  padding: 0.6rem 0.75rem;
  background: color-mix(in oklab, var(--color-base-content) 3%, transparent);
}
.vance-button__row {
  display: flex;
  gap: 0.4rem;
  align-items: center;
}
.vance-button__inp {
  border: 1px solid color-mix(in oklab, var(--color-base-content) 20%, transparent);
  border-radius: 0.25rem;
  padding: 0.3rem 0.5rem;
  font: inherit;
  font-size: 0.85rem;
  background: var(--color-base-100);
  color: inherit;
  box-sizing: border-box;
}
.vance-button__work {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}
.vance-button__btn {
  border: 1px solid var(--color-primary);
  background: var(--color-primary);
  color: var(--color-primary-content);
  border-radius: 0.35rem;
  padding: 0.4rem 1rem;
  font-size: 0.9rem;
  font-weight: 500;
  cursor: pointer;
}
.vance-button__btn:disabled { opacity: 0.55; cursor: default; }
.vance-button__ok { color: var(--color-success); font-size: 0.85rem; }
.vance-button__error {
  color: var(--color-error);
  font-size: 0.8rem;
}
</style>

<script setup lang="ts">
/**
 * NodeView for the {@code vance-input} block.
 *
 * - **work mode** (read-only page): an editable single-line input or
 *   textarea bound to a text document; Save writes the whole document.
 * - **design mode**: a single-line / multi-line toggle (writes the block
 *   attribute); the field is shown disabled as a preview.
 *
 * I/O is done through the host-provided {@code loadText} / {@code saveText}
 * options so the block-editor stays decoupled from REST.
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { NodeViewWrapper } from '@tiptap/vue-3';
import type { Editor } from '@tiptap/core';
import type { Node as ProseMirrorNode } from '@tiptap/pm/model';

interface ExtensionOptions {
  loadText?: ((uri: string) => Promise<string>) | null;
  saveText?: ((uri: string, content: string) => Promise<void>) | null;
}

const props = defineProps<{
  node: ProseMirrorNode;
  updateAttributes: (attrs: Record<string, unknown>) => void;
  editor: Editor;
  extension: { options: ExtensionOptions };
}>();

const config = computed(() => (props.node.attrs?.config as string | null) ?? '');
const multiline = computed(() => props.node.attrs?.multiline === true);

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

const content = ref('');
const baseline = ref('');
const loading = ref(false);
const saving = ref(false);
const error = ref<string | null>(null);
const savedAt = ref(false);

const dirty = computed(() => content.value !== baseline.value);

async function load() {
  const loader = props.extension.options.loadText;
  if (!config.value || !loader) return;
  loading.value = true;
  error.value = null;
  try {
    const text = await loader(config.value);
    content.value = text ?? '';
    baseline.value = content.value;
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Load failed';
  } finally {
    loading.value = false;
  }
}

async function save() {
  const saver = props.extension.options.saveText;
  if (!saver) return;
  saving.value = true;
  error.value = null;
  savedAt.value = false;
  try {
    await saver(config.value, content.value);
    baseline.value = content.value;
    savedAt.value = true;
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Save failed';
  } finally {
    saving.value = false;
  }
}

function cancel() {
  content.value = baseline.value;
  savedAt.value = false;
}

function setMultiline(v: boolean) {
  props.updateAttributes({ multiline: v });
}

onMounted(load);
</script>

<template>
  <NodeViewWrapper as="aside" class="vance-input" :data-config="config">
    <div v-if="error" class="vance-input__error">{{ error }}</div>

    <!-- DESIGN: single/multi toggle + disabled preview -->
    <template v-if="editable">
      <div class="vance-input__design-row" contenteditable="false">
        <label class="vance-input__opt">
          <input
            type="radio"
            :checked="!multiline"
            @change="setMultiline(false)"
            @mousedown.stop
          /> Single line
        </label>
        <label class="vance-input__opt">
          <input
            type="radio"
            :checked="multiline"
            @change="setMultiline(true)"
            @mousedown.stop
          /> Multi line
        </label>
        <span class="vance-input__path">{{ config }}</span>
      </div>
      <textarea
        v-if="multiline"
        class="vance-input__field"
        :value="content"
        rows="3"
        disabled
        contenteditable="false"
      />
      <input
        v-else
        class="vance-input__field"
        :value="content"
        disabled
        contenteditable="false"
      />
    </template>

    <!-- WORK: editable field + Save/Cancel -->
    <template v-else>
      <textarea
        v-if="multiline"
        v-model="content"
        class="vance-input__field"
        rows="3"
        :disabled="saving || loading"
        contenteditable="false"
        @mousedown.stop
        @keydown.stop
      />
      <input
        v-else
        v-model="content"
        class="vance-input__field"
        :disabled="saving || loading"
        contenteditable="false"
        @mousedown.stop
        @keydown.stop
      />
      <div class="vance-input__actions" contenteditable="false">
        <span v-if="savedAt && !dirty" class="vance-input__saved">Gespeichert ✓</span>
        <span class="vance-input__spacer" />
        <button
          v-if="dirty"
          type="button"
          class="vance-input__btn"
          :disabled="saving"
          @click="cancel"
        >Cancel</button>
        <button
          type="button"
          class="vance-input__btn vance-input__btn--primary"
          :disabled="saving || !dirty"
          @click="save"
        >{{ saving ? 'Speichere…' : 'Save' }}</button>
      </div>
    </template>
  </NodeViewWrapper>
</template>

<style scoped>
.vance-input {
  margin: 0.6em 0;
}
.vance-input__error {
  background: oklch(var(--er) / 0.12);
  color: oklch(var(--er));
  font-size: 0.82rem;
  padding: 0.35rem 0.6rem;
  border-radius: 0.25rem;
  margin-bottom: 0.4rem;
}
.vance-input__design-row {
  display: flex;
  align-items: center;
  gap: 0.8rem;
  font-size: 0.8rem;
  color: oklch(var(--bc) / 0.7);
  margin-bottom: 0.35rem;
}
.vance-input__opt {
  display: flex;
  align-items: center;
  gap: 0.25rem;
}
.vance-input__path {
  margin-left: auto;
  font-family: monospace;
  font-size: 0.72rem;
  color: oklch(var(--bc) / 0.5);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.vance-input__field {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid oklch(var(--bc) / 0.2);
  border-radius: 0.3rem;
  padding: 0.35rem 0.5rem;
  font: inherit;
  color: inherit;
  background: oklch(var(--b1));
  outline: none;
  resize: vertical;
}
.vance-input__field:disabled {
  background: oklch(var(--bc) / 0.04);
  color: oklch(var(--bc) / 0.7);
}
.vance-input__actions {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 0.4rem;
}
.vance-input__spacer { flex: 1; }
.vance-input__saved { color: oklch(var(--su)); font-size: 0.78rem; }
.vance-input__btn {
  border: 1px solid oklch(var(--bc) / 0.2);
  border-radius: 0.3rem;
  padding: 0.25rem 0.8rem;
  font-size: 0.82rem;
  background: oklch(var(--b1));
  color: oklch(var(--bc));
  cursor: pointer;
}
.vance-input__btn:disabled { opacity: 0.5; cursor: default; }
.vance-input__btn--primary {
  background: oklch(var(--p));
  color: oklch(var(--pc));
  border-color: oklch(var(--p));
}
</style>

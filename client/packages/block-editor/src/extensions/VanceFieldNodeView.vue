<script setup lang="ts">
/**
 * NodeView for the {@code vance-field} block.
 *
 * - **work mode** (read-only page): the interactive input — radio list
 *   (`choice`), checkbox list (`multi`), `dropdown`, `text` input or
 *   `textarea`. Every change writes the `value` block attribute, which
 *   flows through the editor's normal debounced auto-save into the fence.
 *   `verdict` / `feedback` (form-resolve output) render as green/red
 *   marking around the field.
 * - **design mode**: config inputs — id, type, question, options (one per
 *   line) and solution (index, comma-separated indices, or reference text)
 *   — written into the fence via `updateAttributes`.
 *
 * All native inputs stop event propagation so clicks never leak into
 * ProseMirror's selection / drag-handle machinery.
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useT } from '../useT';
import { NodeViewWrapper } from '@tiptap/vue-3';
import type { Editor } from '@tiptap/core';
import type { Node as ProseMirrorNode } from '@tiptap/pm/model';

const t = useT();

const props = defineProps<{
  node: ProseMirrorNode;
  updateAttributes: (attrs: Record<string, unknown>) => void;
  editor: Editor;
}>();

const FIELD_TYPES = ['choice', 'multi', 'dropdown', 'text', 'textarea'] as const;

const id = computed(() => (props.node.attrs?.id as string | null) ?? '');
const fieldType = computed(() =>
  FIELD_TYPES.includes(props.node.attrs?.fieldType as (typeof FIELD_TYPES)[number])
    ? (props.node.attrs.fieldType as string)
    : 'text',
);
const question = computed(() => (props.node.attrs?.question as string | null) ?? '');
const options = computed(() =>
  Array.isArray(props.node.attrs?.options) ? (props.node.attrs.options as string[]) : [],
);
const solution = computed(() => props.node.attrs?.solution ?? null);
const value = computed(() => props.node.attrs?.value ?? null);
const verdict = computed(() => (props.node.attrs?.verdict as string | null) ?? null);
const feedback = computed(() => (props.node.attrs?.feedback as string | null) ?? null);

const editable = ref(props.editor.isEditable);
function syncEditable() {
  editable.value = props.editor.isEditable;
}
onMounted(() => {
  props.editor.on('update', syncEditable);
  props.editor.on('transaction', syncEditable);
});
onBeforeUnmount(() => {
  props.editor.off('update', syncEditable);
  props.editor.off('transaction', syncEditable);
});

// ── work mode: value helpers ──────────────────────────────────────

function asIndex(v: unknown): number | null {
  return typeof v === 'number' && Number.isInteger(v) ? v : null;
}
function asIndices(v: unknown): number[] {
  return Array.isArray(v) ? v.filter((o): o is number => typeof o === 'number') : [];
}
function asText(v: unknown): string {
  return typeof v === 'string' ? v : '';
}

const closed = computed(() => ['choice', 'multi', 'dropdown'].includes(fieldType.value));

function pick(idx: number) {
  props.updateAttributes({ value: idx });
}
function toggleMulti(idx: number) {
  const cur = asIndices(value.value);
  const next = cur.includes(idx) ? cur.filter((i) => i !== idx) : [...cur, idx];
  props.updateAttributes({ value: next });
}
function setText(v: string) {
  props.updateAttributes({ value: v });
}

// ── design mode: config helpers ───────────────────────────────────

const optionsText = computed(() => options.value.join('\n'));
const solutionText = computed(() => {
  if (solution.value == null) return '';
  return Array.isArray(solution.value)
    ? asIndices(solution.value).join(', ')
    : String(solution.value);
});

function onId(e: Event) {
  props.updateAttributes({ id: (e.target as HTMLInputElement).value });
}
function onType(e: Event) {
  props.updateAttributes({ fieldType: (e.target as HTMLSelectElement).value });
}
function onQuestion(e: Event) {
  props.updateAttributes({ question: (e.target as HTMLInputElement).value });
}
function onOptions(e: Event) {
  props.updateAttributes({
    options: (e.target as HTMLTextAreaElement).value.split('\n').map((l) => l.trimEnd()),
  });
}
function onSolution(e: Event) {
  const raw = (e.target as HTMLInputElement).value.trim();
  if (raw === '') {
    props.updateAttributes({ solution: null });
    return;
  }
  if (fieldType.value === 'multi') {
    const list = raw
      .split(',')
      .map((p) => Number.parseInt(p.trim(), 10))
      .filter((n) => Number.isInteger(n));
    props.updateAttributes({ solution: list.length > 0 ? list : raw });
    return;
  }
  if (fieldType.value === 'choice' || fieldType.value === 'dropdown') {
    const n = Number.parseInt(raw, 10);
    props.updateAttributes({ solution: Number.isInteger(n) ? n : raw });
    return;
  }
  props.updateAttributes({ solution: raw });
}

const verdictClass = computed(() => {
  if (verdict.value === 'correct') return 'vance-field--correct';
  if (verdict.value === 'wrong') return 'vance-field--wrong';
  return '';
});
</script>

<template>
  <NodeViewWrapper as="aside" class="vance-field" :class="verdictClass">
    <!-- DESIGN: config inputs -->
    <div v-if="editable" class="vance-field__design" contenteditable="false">
      <div class="vance-field__row">
        <select class="vance-field__inp vance-field__type" :value="fieldType" @mousedown.stop @change="onType">
          <option value="text">{{ t('blockEditor.field.typeText') }}</option>
          <option value="textarea">{{ t('blockEditor.field.typeTextarea') }}</option>
          <option value="choice">{{ t('blockEditor.field.typeChoice') }}</option>
          <option value="multi">{{ t('blockEditor.field.typeMulti') }}</option>
          <option value="dropdown">{{ t('blockEditor.field.typeDropdown') }}</option>
        </select>
        <input
          class="vance-field__inp vance-field__id"
          :placeholder="t('blockEditor.field.idPlaceholder')"
          :value="id"
          @input="onId"
          @mousedown.stop
          @keydown.stop
        />
      </div>
      <input
        class="vance-field__inp"
        :placeholder="t('blockEditor.field.questionPlaceholder')"
        :value="question"
        @input="onQuestion"
        @mousedown.stop
        @keydown.stop
      />
      <textarea
        v-if="closed"
        class="vance-field__inp"
        rows="3"
        :placeholder="t('blockEditor.field.optionsPlaceholder')"
        :value="optionsText"
        @input="onOptions"
        @mousedown.stop
        @keydown.stop
      />
      <input
        class="vance-field__inp"
        :placeholder="t('blockEditor.field.solutionPlaceholder')"
        :value="solutionText"
        @input="onSolution"
        @mousedown.stop
        @keydown.stop
      />
    </div>

    <!-- WORK: the interactive field -->
    <div v-else class="vance-field__work" contenteditable="false">
      <div class="vance-field__question">{{ question }}</div>

      <!-- choice: radio list -->
      <div v-if="fieldType === 'choice'" class="vance-field__options">
        <label v-for="(opt, i) in options" :key="i" class="vance-field__option">
          <input
            type="radio"
            :name="`vance-field-${id}`"
            :checked="asIndex(value) === i"
            @change="pick(i)"
            @mousedown.stop
            @keydown.stop
          />
          <span>{{ opt }}</span>
        </label>
      </div>

      <!-- multi: checkbox list -->
      <div v-else-if="fieldType === 'multi'" class="vance-field__options">
        <label v-for="(opt, i) in options" :key="i" class="vance-field__option">
          <input
            type="checkbox"
            :checked="asIndices(value).includes(i)"
            @change="toggleMulti(i)"
            @mousedown.stop
            @keydown.stop
          />
          <span>{{ opt }}</span>
        </label>
      </div>

      <!-- dropdown: compact select -->
      <select
        v-else-if="fieldType === 'dropdown'"
        class="vance-field__inp vance-field__select"
        :value="asIndex(value) == null ? '' : String(asIndex(value))"
        @change="pick(Number.parseInt(($event.target as HTMLSelectElement).value, 10))"
        @mousedown.stop
        @keydown.stop
      >
        <option value="" disabled>{{ t('blockEditor.field.selectPlaceholder') }}</option>
        <option v-for="(opt, i) in options" :key="i" :value="String(i)">{{ opt }}</option>
      </select>

      <!-- text / textarea -->
      <textarea
        v-else-if="fieldType === 'textarea'"
        class="vance-field__inp"
        rows="2"
        :placeholder="t('blockEditor.field.answerPlaceholder')"
        :value="asText(value)"
        @input="setText(($event.target as HTMLTextAreaElement).value)"
        @mousedown.stop
        @keydown.stop
      />
      <input
        v-else
        class="vance-field__inp"
        :placeholder="t('blockEditor.field.answerPlaceholder')"
        :value="asText(value)"
        @input="setText(($event.target as HTMLInputElement).value)"
        @mousedown.stop
        @keydown.stop
      />

      <div v-if="verdict || feedback" class="vance-field__verdict">
        <span v-if="verdict === 'correct'" class="vance-field__mark">✓</span>
        <span v-else-if="verdict === 'wrong'" class="vance-field__mark">✗</span>
        <span v-if="feedback">{{ feedback }}</span>
      </div>
    </div>
  </NodeViewWrapper>
</template>

<style scoped>
.vance-field {
  margin: 0.6em 0;
  border-radius: 0.5rem;
}
.vance-field--correct {
  border: 1px solid var(--color-success);
  background: color-mix(in oklab, var(--color-success) 8%, transparent);
}
.vance-field--wrong {
  border: 1px solid var(--color-error);
  background: color-mix(in oklab, var(--color-error) 8%, transparent);
}
.vance-field__design {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
  border: 1px dashed color-mix(in oklab, var(--color-base-content) 30%, transparent);
  border-radius: 0.5rem;
  padding: 0.6rem 0.75rem;
  background: color-mix(in oklab, var(--color-base-content) 3%, transparent);
}
.vance-field__row {
  display: flex;
  gap: 0.4rem;
  align-items: center;
}
.vance-field__type {
  width: auto;
}
.vance-field__id {
  width: 8rem;
}
.vance-field__work {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  padding: 0.5rem 0.75rem;
}
.vance-field__question {
  font-weight: 500;
}
.vance-field__options {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.vance-field__option {
  display: flex;
  align-items: center;
  gap: 0.45rem;
  cursor: pointer;
}
.vance-field__inp {
  border: 1px solid color-mix(in oklab, var(--color-base-content) 20%, transparent);
  border-radius: 0.25rem;
  padding: 0.3rem 0.5rem;
  font: inherit;
  font-size: 0.9rem;
  background: var(--color-base-100);
  color: inherit;
  box-sizing: border-box;
}
.vance-field__select {
  width: auto;
  min-width: 40%;
}
.vance-field__verdict {
  display: flex;
  gap: 0.4rem;
  align-items: baseline;
  font-size: 0.85rem;
}
.vance-field--correct .vance-field__mark {
  color: var(--color-success);
  font-weight: 700;
}
.vance-field--wrong .vance-field__mark {
  color: var(--color-error);
  font-weight: 700;
}
</style>

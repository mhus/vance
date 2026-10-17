<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import VIcon from './VIcon.vue';
import type { FormSuggestionDto } from '@vance/generated';

/**
 * A list of recipients (mail addresses) as chips, with live suggestions
 * while typing. The value stays the plain comma-separated string the
 * handlers already parse, so a field can switch to this input without the
 * server noticing.
 *
 * Suggestions come from `fetchSuggestions` (a field with `suggestionsFrom`).
 * Choosing one inserts each of its values as its own chip — for a group that
 * means every member, so the reader sees and can drop each recipient before
 * sending. Free text still works: Enter, comma, Tab or leaving the field
 * commits what was typed.
 *
 * This package has no i18n; texts come in as props.
 */
interface Props {
  modelValue: string;
  label?: string;
  placeholder?: string;
  help?: string;
  error?: string;
  required?: boolean;
  disabled?: boolean;
  fetchSuggestions?: (query: string) => Promise<FormSuggestionDto[]>;
  removeLabel?: string;
}

const props = withDefaults(defineProps<Props>(), { removeLabel: 'Remove' });

const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>();

const draft = ref('');
const suggestions = ref<FormSuggestionDto[]>([]);
const active = ref(-1);
let timer: ReturnType<typeof setTimeout> | undefined;
let generation = 0;

const recipients = computed(() =>
  props.modelValue.split(/[,;\s]+/).map((s) => s.trim()).filter(Boolean),
);

function write(list: string[]): void {
  const unique: string[] = [];
  for (const r of list) {
    if (!unique.some((u) => u.toLowerCase() === r.toLowerCase())) unique.push(r);
  }
  emit('update:modelValue', unique.join(', '));
}

function commitDraft(): void {
  const parts = draft.value.split(/[,;\s]+/).map((s) => s.trim()).filter(Boolean);
  draft.value = '';
  suggestions.value = [];
  if (parts.length) write([...recipients.value, ...parts]);
}

function choose(s: FormSuggestionDto): void {
  draft.value = '';
  suggestions.value = [];
  active.value = -1;
  write([...recipients.value, ...s.values]);
}

function remove(r: string): void {
  if (props.disabled) return;
  write(recipients.value.filter((x) => x !== r));
}

function onInput(): void {
  clearTimeout(timer);
  active.value = -1;
  const q = draft.value.trim();
  if (!props.fetchSuggestions || q.length < 2) {
    suggestions.value = [];
    return;
  }
  const fetcher = props.fetchSuggestions;
  timer = setTimeout(async () => {
    const mine = ++generation;
    try {
      const hits = await fetcher(q);
      // A slower answer to an older keystroke must not replace a newer one.
      if (mine === generation) suggestions.value = hits;
    } catch {
      if (mine === generation) suggestions.value = [];
    }
  }, 200);
}

function onKey(event: KeyboardEvent): void {
  if (event.key === 'ArrowDown' && suggestions.value.length) {
    event.preventDefault();
    active.value = (active.value + 1) % suggestions.value.length;
  } else if (event.key === 'ArrowUp' && suggestions.value.length) {
    event.preventDefault();
    active.value = active.value <= 0 ? suggestions.value.length - 1 : active.value - 1;
  } else if (event.key === 'Enter' && active.value >= 0) {
    event.preventDefault();
    choose(suggestions.value[active.value]);
  } else if (event.key === 'Escape') {
    suggestions.value = [];
  } else if ((event.key === 'Enter' || event.key === ',' || event.key === ';' || event.key === 'Tab') && draft.value.trim()) {
    event.preventDefault();
    commitDraft();
  } else if (event.key === 'Backspace' && draft.value === '' && recipients.value.length) {
    event.preventDefault();
    remove(recipients.value[recipients.value.length - 1]);
  }
}

function onBlur(): void {
  // Let a click on a suggestion land before the list closes.
  setTimeout(() => {
    if (draft.value.trim()) commitDraft();
    suggestions.value = [];
  }, 150);
}

onBeforeUnmount(() => clearTimeout(timer));

const icon = (kind: string | undefined | null) => (kind === 'group' ? 'team' : kind === 'org' ? 'tenant' : 'user');
</script>

<template>
  <div class="v-field flex flex-col gap-1 w-full relative">
    <span v-if="label" class="v-field-label text-xs font-semibold text-(--v-label-secondary)">{{ label }}</span>
    <div
      :class="['input', 'w-full', 'h-auto', 'min-h-8', 'flex', 'flex-wrap', 'items-center', 'gap-1', 'py-1', { 'input-error': !!error }]"
    >
      <span
        v-for="r in recipients"
        :key="r"
        class="inline-flex items-center gap-1 text-xs px-1.5 py-0.5 rounded bg-(--v-fill)"
      >
        {{ r }}
        <button
          v-if="!disabled"
          type="button"
          class="opacity-60 hover:opacity-100"
          :aria-label="`${removeLabel} ${r}`"
          @click="remove(r)"
        ><VIcon name="close" /></button>
      </span>
      <input
        v-model="draft"
        type="text"
        class="flex-1 min-w-[8rem] bg-transparent outline-none text-sm"
        :placeholder="recipients.length ? '' : placeholder ?? ''"
        :disabled="disabled"
        :required="required && recipients.length === 0"
        autocomplete="off"
        @input="onInput"
        @keydown="onKey"
        @blur="onBlur"
      />
    </div>
    <ul
      v-if="suggestions.length"
      class="absolute z-20 left-0 right-0 top-full mt-1 max-h-72 overflow-auto rounded-(--v-radius-control) bg-(--v-surface-raised) shadow-(--v-shadow-float)"
      role="listbox"
    >
      <li
        v-for="(s, i) in suggestions"
        :key="`${s.label}|${s.values.join(',')}`"
        role="option"
        :aria-selected="i === active"
        :class="['flex items-start gap-2 px-3 py-2 cursor-pointer', i === active ? 'bg-(--v-selection)' : 'hover:bg-(--v-fill)']"
        @mousedown.prevent="choose(s)"
      >
        <span class="mt-0.5 text-(--v-label-secondary)"><VIcon :name="icon(s.kind)" /></span>
        <span class="min-w-0">
          <span class="block text-sm truncate">{{ s.label }}</span>
          <span v-if="s.description" class="block text-xs text-(--v-label-secondary) truncate">{{ s.description }}</span>
          <span v-if="s.note" class="block text-xs text-(--v-warning)">{{ s.note }}</span>
        </span>
      </li>
    </ul>
    <span v-if="error || help" :class="['v-field-hint', 'text-xs', error ? 'text-error' : 'opacity-70']">
      {{ error || help }}
    </span>
  </div>
</template>

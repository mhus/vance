<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, useId } from 'vue';
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
 * Leaving the field commits at once, not after a delay: a click on a
 * dialog's "Send" right after typing must see the typed address in the
 * model. A click on a suggestion never blurs the input (`mousedown.prevent`
 * on the list), so it needs no grace period either.
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

/** Debounce before a keystroke turns into a suggestion request. */
const DEBOUNCE_MS = 200;

const baseId = useId();
const labelId = baseId + '-label';
const listId = baseId + '-list';

const draft = ref('');
const suggestions = ref<FormSuggestionDto[]>([]);
const active = ref(-1);
const listbox = ref<HTMLElement | null>(null);
let timer: ReturnType<typeof setTimeout> | undefined;
/**
 * Bumped whenever a pending or in-flight lookup becomes irrelevant — a newer
 * keystroke, a commit, a choice, Escape, blur. An answer is applied only
 * while its generation is still the current one.
 */
let generation = 0;

const open = computed(() => suggestions.value.length > 0);

const recipients = computed(() =>
  props.modelValue.split(/[,;\s]+/).map((s) => s.trim()).filter(Boolean),
);

function optionId(index: number): string {
  return baseId + '-opt-' + index;
}

function optionKey(s: FormSuggestionDto): string {
  return s.label + '|' + s.values.join(',');
}

function removeAria(r: string): string {
  return props.removeLabel + ' ' + r;
}

const activeDescendant = computed(() =>
  open.value && active.value >= 0 ? optionId(active.value) : undefined,
);

/** Drops a scheduled lookup and invalidates one already on the wire. */
function cancelPending(): void {
  clearTimeout(timer);
  timer = undefined;
  generation++;
}

function closeList(): void {
  cancelPending();
  suggestions.value = [];
  active.value = -1;
}

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
  closeList();
  if (parts.length) write([...recipients.value, ...parts]);
}

function choose(s: FormSuggestionDto): void {
  draft.value = '';
  closeList();
  write([...recipients.value, ...s.values]);
}

function remove(r: string): void {
  if (props.disabled) return;
  write(recipients.value.filter((x) => x !== r));
}

function onInput(): void {
  cancelPending();
  active.value = -1;
  const q = draft.value.trim();
  if (!props.fetchSuggestions || q.length < 2) {
    suggestions.value = [];
    return;
  }
  const fetcher = props.fetchSuggestions;
  const mine = generation;
  timer = setTimeout(async () => {
    timer = undefined;
    try {
      const hits = await fetcher(q);
      // A slower answer to an older keystroke must not replace a newer one.
      if (mine === generation) suggestions.value = hits;
    } catch {
      if (mine === generation) suggestions.value = [];
    }
  }, DEBOUNCE_MS);
}

function scrollActiveIntoView(): void {
  void nextTick(() => {
    listbox.value
      ?.querySelector<HTMLElement>('[data-active="true"]')
      ?.scrollIntoView?.({ block: 'nearest' });
  });
}

function onKey(event: KeyboardEvent): void {
  if (event.key === 'ArrowDown' && open.value) {
    event.preventDefault();
    active.value = (active.value + 1) % suggestions.value.length;
    scrollActiveIntoView();
  } else if (event.key === 'ArrowUp' && open.value) {
    event.preventDefault();
    active.value = active.value <= 0 ? suggestions.value.length - 1 : active.value - 1;
    scrollActiveIntoView();
  } else if (event.key === 'Enter' && open.value && active.value >= 0) {
    event.preventDefault();
    choose(suggestions.value[active.value]);
  } else if (event.key === 'Escape') {
    // An open list is closed by Escape — only that. Without stopping the
    // event here a surrounding dialog would close along with it. With the
    // list closed, Escape belongs to the dialog again.
    if (open.value) {
      event.preventDefault();
      event.stopPropagation();
    }
    closeList();
  } else if (event.key === 'Tab' && draft.value.trim()) {
    // Commit, but let focus move on: Tab must never trap the user here.
    commitDraft();
  } else if ((event.key === 'Enter' || event.key === ',' || event.key === ';') && draft.value.trim()) {
    event.preventDefault();
    commitDraft();
  } else if (event.key === 'Backspace' && draft.value === '' && recipients.value.length) {
    event.preventDefault();
    remove(recipients.value[recipients.value.length - 1]);
  }
}

function onBlur(): void {
  if (draft.value.trim()) commitDraft();
  else closeList();
}

onBeforeUnmount(() => clearTimeout(timer));

const icon = (kind: string | undefined | null) => (kind === 'group' ? 'team' : kind === 'org' ? 'tenant' : 'user');
</script>

<template>
  <div class="v-field flex flex-col gap-1 w-full relative">
    <span v-if="label" :id="labelId" class="v-field-label text-xs font-semibold text-(--v-label-secondary)">{{ label }}</span>
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
          :aria-label="removeAria(r)"
          @click="remove(r)"
        ><VIcon name="close" /></button>
      </span>
      <input
        v-model="draft"
        type="text"
        role="combobox"
        aria-autocomplete="list"
        :aria-labelledby="label ? labelId : undefined"
        :aria-expanded="open"
        :aria-controls="open ? listId : undefined"
        :aria-activedescendant="activeDescendant"
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
    <!-- mousedown.prevent keeps focus in the input, so a click on an option
         (or the list's scrollbar) never blurs it and cannot race the
         blur-commit. -->
    <ul
      v-if="open"
      :id="listId"
      ref="listbox"
      class="absolute z-20 left-0 right-0 top-full mt-1 max-h-72 overflow-auto rounded-(--v-radius-control) bg-(--v-surface-raised) shadow-(--v-shadow-float)"
      role="listbox"
      :aria-labelledby="label ? labelId : undefined"
      @mousedown.prevent
    >
      <li
        v-for="(s, i) in suggestions"
        :id="optionId(i)"
        :key="optionKey(s)"
        role="option"
        :aria-selected="i === active"
        :data-active="i === active || null"
        :class="['flex items-start gap-2 px-3 py-2 cursor-pointer', i === active ? 'bg-(--v-selection)' : 'hover:bg-(--v-fill)']"
        @mousedown.prevent="choose(s)"
        @mousemove="active = i"
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

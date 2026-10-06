<script setup lang="ts">
import type { Option } from './VSelect.vue';
import { computed, nextTick, ref } from 'vue';

/**
 * Editable dropdown: a text input with a filtered option list.
 *
 * <p>Companion to {@code VSelect} for the case where the options are an
 * <em>inventory</em> rather than a closed enum — a live catalog like the
 * LLM model list, where a stored value can outlive its entry. A plain
 * {@code <select>} cannot even display such a value: an option that is
 * not in the list renders as an empty box. This component
 *
 * <ul>
 *   <li>shows the current value verbatim, member of the list or not,</li>
 *   <li>accepts free text — whatever is in the input on blur is the value,</li>
 *   <li>filters the option list (label <em>and</em> value) as you type, so a
 *       long catalog stays navigable by keyboard.</li>
 * </ul>
 *
 * <p>It reuses {@code VSelect}'s {@code Option} shape and the
 * {@code v-field} hook-class convention, so hosts can retarget the markup
 * with the same {@code :deep()} selectors as for the other primitives.
 */
interface Props {
  modelValue: string | null;
  options: Option<string>[];
  label?: string;
  placeholder?: string;
  help?: string;
  error?: string;
  disabled?: boolean;
  /**
   * Hint shown under the input while the current value is not among the
   * options — that is how a stale catalog entry stays recognisable as
   * "gone from the list" instead of "looks selected".
   */
  customValueHint?: string;
  /** Shown inside the dropdown when the filter matches no option. */
  noMatchesHint?: string;
}

const props = withDefaults(defineProps<Props>(), {
  disabled: false,
  customValueHint: 'Custom value (not in the list)',
  noMatchesHint: 'No matches',
});

const emit = defineEmits<{ (e: 'update:modelValue', value: string | null): void }>();

/** Text being typed while the dropdown is open; the closed control shows the model value. */
const query = ref('');
const open = ref(false);
/** Index of the keyboard-highlighted option; -1 = none, the typed text wins. */
const activeIndex = ref(-1);
const listbox = ref<HTMLElement | null>(null);

const displayText = computed(() => (open.value ? query.value : props.modelValue ?? ''));

const visibleOptions = computed(() => {
  const needle = query.value.trim().toLowerCase();
  if (!needle) return props.options;
  return props.options.filter(
    (opt) =>
      opt.label.toLowerCase().includes(needle)
      || opt.value.toLowerCase().includes(needle),
  );
});

/** Whether the committed value is a hand-typed / stale entry rather than a listed one. */
const isCustomValue = computed(() => {
  const v = props.modelValue;
  return !!v && !props.options.some((o) => o.value === v);
});

function openList(): void {
  if (props.disabled || open.value) return;
  query.value = props.modelValue ?? '';
  activeIndex.value = -1;
  open.value = true;
}

function onInput(event: Event): void {
  query.value = (event.target as HTMLInputElement).value;
  open.value = true;
  activeIndex.value = -1;
}

function select(opt: Option<string>): void {
  if (opt.disabled) return;
  emit('update:modelValue', opt.value);
  close();
}

/** Blur / Enter: whatever is in the input becomes the value — the free-text path. */
function commit(): void {
  const trimmed = query.value.trim();
  emit('update:modelValue', trimmed === '' ? null : trimmed);
  close();
}

function close(): void {
  open.value = false;
  activeIndex.value = -1;
}

function onKeydown(event: KeyboardEvent): void {
  switch (event.key) {
    case 'ArrowDown':
      event.preventDefault();
      if (!open.value) {
        openList();
        return;
      }
      activeIndex.value = Math.min(activeIndex.value + 1, visibleOptions.value.length - 1);
      scrollActiveIntoView();
      return;
    case 'ArrowUp':
      event.preventDefault();
      if (!open.value) return;
      activeIndex.value = Math.max(activeIndex.value - 1, 0);
      scrollActiveIntoView();
      return;
    case 'Enter':
      event.preventDefault();
      if (open.value && activeIndex.value >= 0) {
        const opt = visibleOptions.value[activeIndex.value];
        if (opt) select(opt);
        return;
      }
      if (open.value) commit();
      return;
    case 'Escape':
      event.preventDefault();
      // Cancel: drop the typed text, keep the committed value untouched.
      close();
      return;
    default:
      return;
  }
}

function scrollActiveIntoView(): void {
  void nextTick(() => {
    listbox.value
      ?.querySelector<HTMLElement>('[data-active="true"]')
      ?.scrollIntoView({ block: 'nearest' });
  });
}
</script>

<template>
  <label class="v-field flex flex-col gap-1 w-full">
    <span v-if="label" class="v-field-label text-sm">{{ label }}</span>
    <div class="relative w-full">
      <input
        type="text"
        role="combobox"
        :value="displayText"
        :placeholder="placeholder"
        :disabled="disabled"
        :class="['input', 'w-full', 'pr-8', { 'input-error': !!error }]"
        autocomplete="off"
        @focus="openList"
        @input="onInput"
        @blur="commit"
        @keydown="onKeydown"
      />
      <span class="pointer-events-none absolute inset-y-0 right-3 flex items-center text-xs opacity-60">▾</span>
      <!-- mousedown.prevent keeps focus on the input, so a click on an option
           lands before the blur-commit and cannot race it. -->
      <ul
        v-if="open"
        ref="listbox"
        class="absolute z-20 left-0 right-0 top-full mt-1 max-h-60 overflow-y-auto border border-base-300 rounded-lg bg-base-100 shadow-lg"
      >
        <li
          v-for="(opt, idx) in visibleOptions"
          :key="opt.value"
          :data-active="idx === activeIndex || null"
          :class="[
            'px-3 py-2 text-sm truncate',
            opt.disabled ? 'opacity-40 cursor-not-allowed' : 'cursor-pointer',
            idx === activeIndex ? 'bg-base-200' : '',
            opt.value === modelValue ? 'font-semibold' : '',
          ]"
          @mousedown.prevent
          @click="select(opt)"
          @mousemove="activeIndex = idx"
        >
          {{ opt.label }}
        </li>
        <li v-if="visibleOptions.length === 0" class="px-3 py-2 text-xs italic opacity-60">
          {{ noMatchesHint }}
        </li>
      </ul>
    </div>
    <span
      v-if="error || help || isCustomValue"
      :class="['v-field-hint', 'text-xs', error ? 'text-error' : isCustomValue ? 'text-warning' : 'opacity-70']"
    >
      {{ error || (isCustomValue ? customValueHint : help) }}
    </span>
  </label>
</template>

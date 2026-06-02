<script setup lang="ts">
import { ref } from 'vue';

interface Props {
  modelValue: string | null | undefined;
  disabled?: boolean;
  label?: string;
  /** Optional placeholder when no emoji is set. Defaults to '💬'. */
  placeholder?: string;
}

const props = withDefaults(defineProps<Props>(), {
  disabled: false,
  placeholder: '💬',
});

const emit = defineEmits<{
  (e: 'update:modelValue', value: string | null): void;
}>();

const open = ref(false);
const custom = ref('');

// A small curated set of topic emojis. Order roughly: planning, coding,
// design, debug, research, communication, scheduling. Kept small so the
// picker fits inside a card without scrolling.
const EMOJIS: ReadonlyArray<string> = [
  '💡', '📝', '✏️', '📌', '📋', '🧩',
  '💻', '🛠️', '⚙️', '🔧', '🧪', '🚀',
  '🐛', '🩹', '🔍', '🧠', '📊', '📈',
  '🎨', '🖼️', '🧵', '🗂️', '📚', '📦',
  '🤖', '🦄', '🌱', '🔥', '⭐', '✅',
  '⚠️', '❓', '💬', '🗒️', '📢', '🎯',
];

function pick(emoji: string): void {
  if (props.disabled) return;
  emit('update:modelValue', emoji);
  open.value = false;
}

function clear(): void {
  if (props.disabled) return;
  emit('update:modelValue', null);
  open.value = false;
}

function applyCustom(): void {
  if (props.disabled) return;
  const trimmed = custom.value.trim();
  if (!trimmed) return;
  // Take only the first cluster — guard against an accidental
  // multi-emoji paste. Intl.Segmenter is available in modern browsers.
  let value = trimmed;
  if (typeof Intl !== 'undefined' && typeof Intl.Segmenter === 'function') {
    const segmenter = new Intl.Segmenter(undefined, { granularity: 'grapheme' });
    const first = segmenter.segment(trimmed)[Symbol.iterator]().next();
    if (!first.done) value = first.value.segment;
  }
  emit('update:modelValue', value);
  custom.value = '';
  open.value = false;
}
</script>

<template>
  <div class="flex flex-col gap-1">
    <span v-if="label" class="text-xs opacity-70">{{ label }}</span>
    <div class="relative inline-block">
      <button
        type="button"
        :disabled="disabled"
        class="inline-flex items-center justify-center size-9 rounded-md border border-base-300 bg-base-100 hover:bg-base-200 text-2xl leading-none"
        :class="disabled ? 'cursor-not-allowed opacity-60' : 'cursor-pointer'"
        :aria-label="modelValue ? `Emoji ${modelValue}` : 'Pick emoji'"
        @click="open = !open"
      >
        <span v-if="modelValue">{{ modelValue }}</span>
        <span v-else class="opacity-40">{{ placeholder }}</span>
      </button>

      <div
        v-if="open"
        class="absolute z-30 mt-2 w-72 rounded-md border border-base-300 bg-base-100 shadow-lg p-3 flex flex-col gap-3"
      >
        <div class="grid grid-cols-6 gap-1">
          <button
            v-for="emoji in EMOJIS"
            :key="emoji"
            type="button"
            class="size-9 rounded hover:bg-base-200 text-xl leading-none"
            :class="modelValue === emoji ? 'bg-base-200 ring-2 ring-primary' : ''"
            @click="pick(emoji)"
          >
            {{ emoji }}
          </button>
        </div>
        <div class="flex items-center gap-2">
          <input
            v-model="custom"
            type="text"
            class="input input-sm input-bordered flex-1 text-lg"
            placeholder="🎲 …"
            maxlength="8"
            @keyup.enter="applyCustom"
          />
          <button
            type="button"
            class="btn btn-xs"
            :disabled="!custom.trim()"
            @click="applyCustom"
          >
            ✓
          </button>
        </div>
        <button
          v-if="modelValue"
          type="button"
          class="btn btn-xs btn-ghost self-start"
          @click="clear"
        >
          ×
        </button>
      </div>
    </div>
  </div>
</template>

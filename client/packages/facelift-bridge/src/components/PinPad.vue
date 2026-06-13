<script setup lang="ts">
import { computed } from 'vue';
import { MAX_PIN_LENGTH } from '@/lock/lockStore';

interface Props {
  value: string;
  maxLength?: number;
}
const props = withDefaults(defineProps<Props>(), {
  maxLength: MAX_PIN_LENGTH,
});

const emit = defineEmits<{
  (e: 'update:value', value: string): void;
  (e: 'submit'): void;
}>();

function press(digit: string): void {
  if (props.value.length >= props.maxLength) return;
  emit('update:value', props.value + digit);
}

function backspace(): void {
  if (props.value.length === 0) return;
  emit('update:value', props.value.slice(0, -1));
}

const dots = computed(() => {
  return Array.from({ length: props.maxLength }, (_, i) => i < props.value.length);
});

const keys: ReadonlyArray<readonly string[]> = [
  ['1', '2', '3'],
  ['4', '5', '6'],
  ['7', '8', '9'],
  ['', '0', '⌫'],
];
</script>

<template>
  <div class="flex flex-col items-center">
    <!-- Dot indicator. -->
    <div class="mb-8 flex gap-3">
      <span
        v-for="(filled, idx) in dots"
        :key="idx"
        class="h-3 w-3 rounded-full border-2 border-gray-500"
        :class="filled ? 'bg-blue-400 border-blue-400' : ''"
      ></span>
    </div>

    <!-- Number pad. -->
    <div class="grid grid-cols-3 gap-3">
      <template v-for="(row, ri) in keys" :key="ri">
        <button
          v-for="(k, ki) in row"
          :key="`${ri}-${ki}`"
          type="button"
          class="flex h-16 w-16 items-center justify-center rounded-full text-2xl font-medium"
          :class="
            k === ''
              ? 'invisible'
              : k === '⌫'
                ? 'text-gray-300'
                : 'bg-gray-800 text-white active:bg-gray-700'
          "
          :disabled="k === ''"
          @click="k === '⌫' ? backspace() : k !== '' ? press(k) : null"
        >
          {{ k }}
        </button>
      </template>
    </div>

    <button
      type="button"
      class="mt-8 rounded bg-blue-500 px-5 py-2 text-sm font-medium text-white disabled:opacity-40"
      :disabled="value.length < 4"
      @click="emit('submit')"
    >
      Submit
    </button>
  </div>
</template>

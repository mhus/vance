<script setup lang="ts">
import { computed } from 'vue';
import { VButton, VIcon } from '@vance/components';
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
  const next = props.value + digit;
  emit('update:value', next);
  // Auto-submit when the user filled the max-length slot — saves a
  // tap on 6-digit PINs. Shorter PINs (4–5 digits) still need an
  // explicit Submit because we can't tell when the user is done.
  if (next.length === props.maxLength) {
    emit('submit');
  }
}

function backspace(): void {
  if (props.value.length === 0) return;
  emit('update:value', props.value.slice(0, -1));
}

const dots = computed(() => {
  return Array.from({ length: props.maxLength }, (_, i) => i < props.value.length);
});

// BACKSPACE marks the erase key; it renders as an icon, not a glyph
// (corporate-design §5).
const BACKSPACE = 'backspace';

const keys: ReadonlyArray<readonly string[]> = [
  ['1', '2', '3'],
  ['4', '5', '6'],
  ['7', '8', '9'],
  ['', '0', BACKSPACE],
];
</script>

<template>
  <div class="flex flex-col items-center">
    <!-- Dot indicator: neutral ring, accent when filled. -->
    <div class="mb-8 flex gap-3">
      <span
        v-for="(filled, idx) in dots"
        :key="idx"
        class="h-3 w-3 rounded-full border-2 transition-colors duration-150 ease-out"
        :class="filled ? 'border-(--v-accent) bg-(--v-accent)' : 'border-(--v-label-tertiary)'"
      ></span>
    </div>

    <!-- Number pad: a phone keypad — neutral circles on the fill, digits in
         the label colour; the erase key has no circle. -->
    <div class="grid grid-cols-3 gap-3">
      <template v-for="(row, ri) in keys" :key="ri">
        <button
          v-for="(k, ki) in row"
          :key="`${ri}-${ki}`"
          type="button"
          class="flex h-16 w-16 items-center justify-center rounded-full text-2xl font-medium transition-colors duration-150 ease-out focus-visible:shadow-[0_0_0_3px_var(--v-focus-ring)] focus-visible:outline-none"
          :class="
            k === ''
              ? 'invisible'
              : k === BACKSPACE
                ? 'text-(--v-label-secondary) active:text-(--v-label)'
                : 'bg-(--v-fill) text-(--v-label) active:bg-(--v-border)'
          "
          :aria-label="k === BACKSPACE ? 'Delete digit' : undefined"
          :disabled="k === ''"
          @click="k === BACKSPACE ? backspace() : k !== '' ? press(k) : null"
        >
          <VIcon v-if="k === BACKSPACE" name="backspace" :size="20" />
          <template v-else>{{ k }}</template>
        </button>
      </template>
    </div>

    <VButton
      variant="primary"
      class="mt-8"
      :disabled="value.length < 4"
      @click="emit('submit')"
    >
      Submit
    </VButton>
  </div>
</template>

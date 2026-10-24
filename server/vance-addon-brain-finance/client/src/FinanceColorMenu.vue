<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import { VColorPicker } from '@vance/components';
import { AccentColor } from '@vance/generated';
import { nodeColorClass, nodeColorStyle } from './nodeColor';
import { useT } from './i18n';

// Small popover over the shared document-color swatches. The wire value is
// the AccentColor enum name (same as documents/kanban); a legacy free-form
// value (e.g. "#4f8") is still shown on the trigger until a swatch replaces it.
const props = defineProps<{
  modelValue: string | null | undefined;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', value: string | undefined): void;
}>();

const t = useT();
const open = ref(false);
const root = ref<HTMLElement | null>(null);

function asAccent(v: string | null | undefined): AccentColor | null {
  return v && (Object.values(AccentColor) as string[]).includes(v) ? (v as AccentColor) : null;
}

function pick(value: AccentColor | null): void {
  emit('update:modelValue', value ?? undefined);
  open.value = false;
}

function onOutside(e: PointerEvent): void {
  if (root.value && !root.value.contains(e.target as Node)) open.value = false;
}

watch(open, (o) => {
  if (o) document.addEventListener('pointerdown', onOutside);
  else document.removeEventListener('pointerdown', onOutside);
});

onBeforeUnmount(() => document.removeEventListener('pointerdown', onOutside));
</script>

<template>
  <div ref="root" class="relative inline-block">
    <button
      type="button"
      class="inline-flex items-center justify-center size-9 rounded-md border border-base-300 bg-base-100 hover:bg-base-200 cursor-pointer"
      :aria-label="t('finance.fields.pickColor')"
      @click="open = !open"
    >
      <span
        v-if="props.modelValue"
        class="size-4 rounded-full"
        :class="nodeColorClass(props.modelValue)"
        :style="nodeColorStyle(props.modelValue)"
      />
      <span v-else class="size-4 rounded-full border border-dashed border-base-content/40" />
    </button>
    <div
      v-if="open"
      class="absolute z-30 mt-2 w-56 rounded-md border border-base-300 bg-base-100 shadow-lg p-3"
    >
      <VColorPicker :model-value="asAccent(props.modelValue)" @update:model-value="pick" />
    </div>
  </div>
</template>

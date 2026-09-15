<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue';

interface Props {
  modelValue: string;
  label?: string;
  placeholder?: string;
  help?: string;
  error?: string;
  rows?: number;
  required?: boolean;
  disabled?: boolean;
  /**
   * Monospace text. Defaults to `true` because this component grew up around
   * code and YAML, where alignment carries meaning — every existing caller
   * expects that and keeps it without changing a line.
   *
   * Pass `false` for **prose**: a teaser, a note, a description. Monospace
   * there reads as "this is data", which is exactly the wrong hint for a
   * sentence somebody is writing in their own words.
   */
  mono?: boolean;
  /**
   * Frameless variant for hosts that draw their own container around the
   * field (the chat composer card). Renders without the DaisyUI `textarea`
   * class, so no border, no box-shadow and no `min-height` — the host's
   * container owns the visual frame and the focus ring.
   */
  plain?: boolean;
  /**
   * Grow with the content: `rows` becomes the minimum height and the field
   * expands line by line up to `maxRows`, then scrolls. The manual resize
   * handle is suppressed because it would fight the computed height.
   */
  autoGrow?: boolean;
  /** Upper growth bound in text lines while {@link autoGrow} is on. */
  maxRows?: number;
}

const props = withDefaults(defineProps<Props>(), {
  rows: 8,
  required: false,
  disabled: false,
  mono: true,
  plain: false,
  autoGrow: false,
  maxRows: 10,
});

const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>();

const fieldRef = ref<HTMLTextAreaElement | null>(null);

/**
 * Re-fit the height to the content. Sets `height: auto` first so deleted
 * lines collapse the field again, then caps at maxRows × line-height (plus
 * vertical padding) — beyond the cap the field scrolls instead of growing.
 * With autoGrow off, any previously set inline height is removed so the
 * plain `rows` attribute rules again.
 */
function fitHeight(): void {
  const el = fieldRef.value;
  if (!el) return;
  if (!props.autoGrow) {
    el.style.height = '';
    el.style.overflowY = '';
    return;
  }
  const cs = getComputedStyle(el);
  const line = parseFloat(cs.lineHeight) || 20;
  const pad = parseFloat(cs.paddingTop) + parseFloat(cs.paddingBottom);
  const maxPx = props.maxRows * line + pad;
  el.style.height = 'auto';
  el.style.height = `${Math.min(el.scrollHeight, maxPx)}px`;
  el.style.overflowY = el.scrollHeight > maxPx ? 'auto' : 'hidden';
}

watch(
  () => [props.modelValue, props.autoGrow, props.maxRows, props.rows],
  () => void nextTick(fitHeight),
  { flush: 'post' },
);
onMounted(() => void nextTick(fitHeight));
</script>

<template>
  <!-- See VInput for the `v-field*` hook-class convention. -->
  <label class="v-field flex flex-col gap-1 w-full">
    <span v-if="label" class="v-field-label text-sm">{{ label }}</span>
    <textarea
      ref="fieldRef"
      :value="modelValue"
      :placeholder="placeholder"
      :rows="rows"
      :required="required"
      :disabled="disabled"
      :class="plain
        ? ['w-full bg-transparent border-none outline-none shadow-none resize-none py-1 disabled:opacity-60', mono ? 'font-mono' : '']
        : ['textarea', 'w-full', mono ? 'font-mono' : '', { 'textarea-error': !!error, 'resize-none': autoGrow }]"
      @input="(e) => emit('update:modelValue', (e.target as HTMLTextAreaElement).value)"
    />
    <span v-if="error || help" :class="['v-field-hint', 'text-xs', error ? 'text-error' : 'opacity-70']">
      {{ error || help }}
    </span>
  </label>
</template>

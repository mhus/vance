<script setup lang="ts">
import { computed } from 'vue';

// Thin wrapper over DaisyUI's `badge` so editors don't hardcode DaisyUI
// component classes (web-ui §7). Renders the exact same classes it always
// did — purely centralization, no visual change.
type Variant =
  | 'neutral' | 'primary' | 'secondary' | 'accent'
  | 'ghost' | 'info' | 'success' | 'warning' | 'error';

interface Props {
  variant?: Variant;
  size?: 'xs' | 'sm' | 'md';
  outline?: boolean;
  // Tinted instead of filled: the variant colour mixed a few percent into the
  // page background, which stays light in the light theme and dark in the dark
  // one. Additive, default off — no existing consumer changes.
  soft?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  variant: 'neutral',
  size: 'md',
  outline: false,
  soft: false,
});

const variantClass = computed<string>(() =>
  props.variant === 'neutral' ? '' : `badge-${props.variant}`);

const sizeClass = computed<string>(() => {
  switch (props.size) {
    case 'xs': return 'badge-xs';
    case 'sm': return 'badge-sm';
    default: return '';
  }
});
</script>

<template>
  <span
    :class="['badge', variantClass, sizeClass, { 'badge-outline': outline, 'badge-soft': soft }]"
  ><slot /></span>
</template>

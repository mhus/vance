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
}

const props = withDefaults(defineProps<Props>(), {
  variant: 'neutral',
  size: 'md',
  outline: false,
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
  <span :class="['badge', variantClass, sizeClass, { 'badge-outline': outline }]"><slot /></span>
</template>

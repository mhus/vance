<script setup lang="ts">
import { computed } from 'vue';

// A small status pill (corporate-design.md §3.3): the meaning colour as text
// on a tint of itself — never a filled colour block. Neutral is grey. The
// variant names are the old DaisyUI ones so every consumer keeps working;
// they collapse onto the CI meanings (primary/accent/info → accent,
// secondary/ghost/neutral → neutral). `outline` and `soft` are accepted for
// the same reason and render the one CI form.
type Variant =
  | 'neutral' | 'primary' | 'secondary' | 'accent'
  | 'ghost' | 'info' | 'success' | 'warning' | 'error';

interface Props {
  variant?: Variant;
  size?: 'xs' | 'sm' | 'md';
  outline?: boolean;
  soft?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  variant: 'neutral',
  size: 'md',
  outline: false,
  soft: false,
});

const meaningClass = computed<string>(() => {
  switch (props.variant) {
    case 'primary':
    case 'accent':
    case 'info': return 'v-badge-accent';
    case 'success': return 'v-badge-success';
    case 'warning': return 'v-badge-warning';
    case 'error': return 'v-badge-danger';
    default: return 'v-badge-neutral';
  }
});
</script>

<template>
  <span :class="['v-badge', meaningClass, `v-badge-${size}`]"><slot /></span>
</template>

<style scoped>
.v-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
  border-radius: 999px;
  font-weight: 500;
  white-space: nowrap;
  color: var(--badge-fg);
  background: color-mix(in srgb, var(--badge-fg) 12%, transparent);
}
.v-badge-md { height: 20px; padding: 0 8px; font-size: 12px; }
.v-badge-sm { height: 18px; padding: 0 6px; font-size: 11px; }
.v-badge-xs { height: 16px; padding: 0 5px; font-size: 10px; }
.v-badge-neutral { --badge-fg: var(--v-label-secondary); }
.v-badge-accent { --badge-fg: var(--v-accent-text); }
.v-badge-success { --badge-fg: var(--v-success); }
.v-badge-warning { --badge-fg: var(--v-warning); }
.v-badge-danger { --badge-fg: var(--v-danger); }
</style>

<script setup lang="ts">
// A text button in one of the four roles of corporate-design.md §6.1:
//
//   primary    navy, filled — at most ONE per view or dialog
//   secondary  neutral surface with a hairline border — every other text action
//   plain      no surface, accent text — tertiary actions, links in forms
//   danger     red text; `filled` only for the confirming button of a
//              destructive dialog
//
// An action shown as an icon alone is VIconButton, not this. `icon` puts a
// leading icon in front of the label (menus, the primary "New document").
// There are no other variants on purpose — a fifth look is how the UI drifted
// before.
import { computed } from 'vue';
import VIcon from './VIcon.vue';
import type { IconName } from './icons';

type Variant = 'primary' | 'secondary' | 'plain' | 'danger';

interface Props {
  variant?: Variant;
  /** Renders an anchor tag instead of a button when set. */
  href?: string;
  type?: 'button' | 'submit' | 'reset';
  loading?: boolean;
  disabled?: boolean;
  block?: boolean;
  size?: 'xs' | 'sm' | 'md';
  /** Leading icon from the UI vocabulary (icons.ts). */
  icon?: IconName;
  /** With `variant="danger"`: the filled red button of a confirmation. */
  filled?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  variant: 'primary',
  type: 'button',
  loading: false,
  disabled: false,
  block: false,
  size: 'md',
  filled: false,
});

defineEmits<{ (e: 'click', event: MouseEvent): void }>();

// Record lookups instead of switches: a missing variant would fail vue-tsc,
// so exhaustiveness stays type-enforced and the computed always returns.
const VARIANT_CLASSES: Record<Variant, string> = {
  primary: 'btn-primary',
  secondary: 'v-btn-secondary',
  plain: 'btn-ghost v-btn-plain',
  danger: 'btn-ghost v-btn-danger',
};

const SIZE_CLASSES: Record<'xs' | 'sm' | 'md', string> = {
  xs: 'btn-xs',
  sm: 'btn-sm',
  md: '',
};

const variantClass = computed<string>(() => {
  if (props.variant === 'danger' && props.filled) return 'btn-error';
  return VARIANT_CLASSES[props.variant];
});

const sizeClass = computed<string>(() => SIZE_CLASSES[props.size]);
</script>

<template>
  <a
    v-if="href"
    :href="href"
    :class="['btn v-btn', variantClass, sizeClass, { 'btn-block': block, 'btn-disabled': disabled }]"
    @click="(e) => $emit('click', e)"
  >
    <span v-if="loading" class="loading loading-spinner loading-sm" />
    <VIcon v-else-if="icon" :name="icon" :size="16" />
    <slot />
  </a>
  <button
    v-else
    :type="type"
    :disabled="disabled || loading"
    :class="['btn v-btn', variantClass, sizeClass, { 'btn-block': block }]"
    @click="(e) => $emit('click', e)"
  >
    <span v-if="loading" class="loading loading-spinner loading-sm" />
    <VIcon v-else-if="icon" :name="icon" :size="16" />
    <slot />
  </button>
</template>

<style scoped>
/* Heights per corporate-design §6.1/§6.3: 32 / 28 / 24 px. Set on the
   component rather than DaisyUI's global --size-field, so raw DaisyUI controls
   not yet migrated keep their size. Unlayered, so it wins over daisy's layer. */
.v-btn { --size: 2rem; font-weight: 500; box-shadow: none; }
.v-btn.btn-sm { --size: 1.75rem; }
.v-btn.btn-xs { --size: 1.5rem; }
.v-btn:focus-visible { outline: none; box-shadow: 0 0 0 3px var(--v-focus-ring); }
.btn.v-btn-secondary {
  background: var(--v-surface);
  border-color: var(--v-border);
  color: var(--v-label);
}
.btn.v-btn-secondary:hover:not(:disabled) { background: var(--v-fill); }
.btn.v-btn-plain { color: var(--v-accent-text); }
.btn.v-btn-danger { color: var(--v-danger); }
.btn.v-btn-plain:hover:not(:disabled),
.btn.v-btn-danger:hover:not(:disabled) { background: var(--v-fill); border-color: transparent; }
</style>

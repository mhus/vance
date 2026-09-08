<script setup lang="ts">
import { computed } from 'vue';

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'link' | 'neutral';

interface Props {
  variant?: Variant;
  /** Renders an anchor tag instead of a button when set. */
  href?: string;
  type?: 'button' | 'submit' | 'reset';
  loading?: boolean;
  disabled?: boolean;
  block?: boolean;
  size?: 'xs' | 'sm' | 'md';
  /** DaisyUI outline style — combinable with any variant. */
  outline?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  variant: 'primary',
  type: 'button',
  loading: false,
  disabled: false,
  block: false,
  size: 'md',
  outline: false,
});

defineEmits<{ (e: 'click', event: MouseEvent): void }>();

// Record lookups instead of switches: a missing variant would fail vue-tsc,
// so exhaustiveness stays type-enforced and the computed always returns.
const VARIANT_CLASSES: Record<Variant, string> = {
  primary: 'btn-primary',
  secondary: 'btn-secondary',
  ghost: 'btn-ghost',
  danger: 'btn-error',
  link: 'btn-link',
  neutral: '',
};

const SIZE_CLASSES: Record<'xs' | 'sm' | 'md', string> = {
  xs: 'btn-xs',
  sm: 'btn-sm',
  md: '',
};

const variantClass = computed<string>(() => VARIANT_CLASSES[props.variant]);

const sizeClass = computed<string>(() => SIZE_CLASSES[props.size]);
</script>

<template>
  <a
    v-if="href"
    :href="href"
    :class="['btn', variantClass, sizeClass, { 'btn-outline': outline, 'btn-block': block, 'btn-disabled': disabled }]"
    @click="(e) => $emit('click', e)"
  >
    <span v-if="loading" class="loading loading-spinner loading-sm" />
    <slot />
  </a>
  <button
    v-else
    :type="type"
    :disabled="disabled || loading"
    :class="['btn', variantClass, sizeClass, { 'btn-outline': outline, 'btn-block': block }]"
    @click="(e) => $emit('click', e)"
  >
    <span v-if="loading" class="loading loading-spinner loading-sm" />
    <slot />
  </button>
</template>

<script setup lang="ts">
import { computed } from 'vue';

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'link';

interface Props {
  variant?: Variant;
  /** Renders an anchor tag instead of a button when set. */
  href?: string;
  type?: 'button' | 'submit' | 'reset';
  loading?: boolean;
  disabled?: boolean;
  block?: boolean;
  size?: 'sm' | 'md';
}

const props = withDefaults(defineProps<Props>(), {
  variant: 'primary',
  type: 'button',
  loading: false,
  disabled: false,
  block: false,
  size: 'md',
});

defineEmits<{ (e: 'click', event: MouseEvent): void }>();

const variantClass = computed<string>(() => {
  switch (props.variant) {
    case 'primary': return 'btn-primary';
    case 'secondary': return 'btn-secondary';
    case 'ghost': return 'btn-ghost';
    case 'danger': return 'btn-error';
    case 'link': return 'btn-link';
  }
});

const sizeClass = computed<string>(() => (props.size === 'sm' ? 'btn-sm' : ''));
</script>

<template>
  <a
    v-if="href"
    :href="href"
    :class="['btn', variantClass, sizeClass, { 'btn-block': block, 'btn-disabled': disabled }]"
    @click="(e) => $emit('click', e)"
  >
    <span v-if="loading" class="loading loading-spinner loading-sm" />
    <slot />
  </a>
  <button
    v-else
    :type="type"
    :disabled="disabled || loading"
    :class="['btn', variantClass, sizeClass, { 'btn-block': block }]"
    @click="(e) => $emit('click', e)"
  >
    <span v-if="loading" class="loading loading-spinner loading-sm" />
    <slot />
  </button>
</template>

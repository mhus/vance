<script setup lang="ts">
import { computed } from 'vue';

type Variant = 'info' | 'warning' | 'error' | 'success';

interface Props {
  variant?: Variant;
}

const props = withDefaults(defineProps<Props>(), { variant: 'info' });

// Record lookup instead of a switch: a missing variant would fail vue-tsc,
// so exhaustiveness stays type-enforced and the computed always returns.
const VARIANT_CLASSES: Record<Variant, string> = {
  info: 'alert-info',
  warning: 'alert-warning',
  error: 'alert-error',
  success: 'alert-success',
};

const variantClass = computed<string>(() => VARIANT_CLASSES[props.variant]);
</script>

<template>
  <div role="alert" :class="['alert', variantClass]">
    <slot />
  </div>
</template>

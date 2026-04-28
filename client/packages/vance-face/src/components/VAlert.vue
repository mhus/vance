<script setup lang="ts">
import { computed } from 'vue';

type Variant = 'info' | 'warning' | 'error' | 'success';

interface Props {
  variant?: Variant;
}

const props = withDefaults(defineProps<Props>(), { variant: 'info' });

const variantClass = computed<string>(() => {
  switch (props.variant) {
    case 'info': return 'alert-info';
    case 'warning': return 'alert-warning';
    case 'error': return 'alert-error';
    case 'success': return 'alert-success';
  }
});
</script>

<template>
  <div role="alert" :class="['alert', variantClass]">
    <slot />
  </div>
</template>

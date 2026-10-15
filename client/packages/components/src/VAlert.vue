<script setup lang="ts">
// A permanent notice (corporate-design.md §3.3): a tinted surface — the
// meaning colour at a few percent — with the icon in that colour and the text
// in the normal label colour. Never a solid coloured block; loud colour is for
// meaning, and a whole paragraph is not a status dot.
import { computed } from 'vue';
import VIcon from './VIcon.vue';
import type { IconName } from './icons';

type Variant = 'info' | 'warning' | 'error' | 'success';

interface Props {
  variant?: Variant;
}

const props = withDefaults(defineProps<Props>(), { variant: 'info' });

// Record lookups instead of a switch: a missing variant would fail vue-tsc,
// so exhaustiveness stays type-enforced and the computed always returns.
const VARIANT_ICON: Record<Variant, IconName> = {
  info: 'info',
  warning: 'warning',
  error: 'error',
  success: 'success',
};

const VARIANT_TONE: Record<Variant, string> = {
  info: 'var(--v-accent-text)',
  warning: 'var(--v-warning)',
  error: 'var(--v-danger)',
  success: 'var(--v-success)',
};

const icon = computed<IconName>(() => VARIANT_ICON[props.variant]);
const tone = computed<string>(() => VARIANT_TONE[props.variant]);
</script>

<template>
  <div role="alert" class="v-alert" :style="{ '--v-alert-tone': tone }">
    <VIcon :name="icon" class="v-alert-icon" />
    <div class="v-alert-body"><slot /></div>
  </div>
</template>

<style scoped>
.v-alert {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border-radius: var(--v-radius-control);
  background: color-mix(in srgb, var(--v-alert-tone) 10%, transparent);
  color: var(--v-label);
  font-size: 13px;
  line-height: 20px;
}
.v-alert-icon { margin-top: 2px; color: var(--v-alert-tone); }
.v-alert-body { flex: 1 1 auto; min-width: 0; }
</style>

<script setup lang="ts">
// One icon of the UI vocabulary (corporate-design.md §5). Takes a meaning
// from icons.ts, never a glyph name — see there for why.
import { computed } from 'vue';
import { ICONS, type IconName } from './icons';

interface Props {
  name: IconName;
  /** 16 in rows and dense bars, 20 in toolbars and sidebar heads. */
  size?: 16 | 20;
}

const props = withDefaults(defineProps<Props>(), { size: 16 });

const glyph = computed(() => ICONS[props.name]);
</script>

<template>
  <component
    :is="glyph"
    :size="size"
    :stroke-width="1.75"
    class="v-icon shrink-0"
    aria-hidden="true"
  />
</template>

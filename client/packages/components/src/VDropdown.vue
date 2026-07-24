<script setup lang="ts">
import { computed } from 'vue';

// Wrapper over DaisyUI's focus-based `dropdown` + `menu` so editors don't
// hardcode those component classes (web-ui §7). Same structure/behaviour as
// before — a focusable trigger opens the menu, blur closes it. The `#trigger`
// slot supplies the trigger label; the default slot supplies the `<li>` items.
interface Props {
  /** Menu open direction. */
  position?: 'bottom' | 'top' | 'end';
  triggerVariant?: 'ghost' | 'primary' | 'secondary';
  triggerSize?: 'xs' | 'sm';
  triggerDisabled?: boolean;
  triggerTitle?: string;
  /** Extra Tailwind for the menu box (typically a width like `w-56`). */
  menuClass?: string;
}

const props = withDefaults(defineProps<Props>(), {
  position: 'bottom',
  triggerVariant: 'ghost',
  triggerSize: 'xs',
  triggerDisabled: false,
  triggerTitle: undefined,
  menuClass: '',
});

const positionClass = computed<string>(() => {
  switch (props.position) {
    case 'top': return 'dropdown-top';
    case 'end': return 'dropdown-end';
    default: return '';
  }
});

const triggerClass = computed<(string | Record<string, boolean>)[]>(() => [
  'btn',
  `btn-${props.triggerVariant}`,
  props.triggerSize === 'sm' ? 'btn-sm' : 'btn-xs',
  { 'btn-disabled': props.triggerDisabled },
]);
</script>

<template>
  <div :class="['dropdown', positionClass]">
    <div tabindex="0" role="button" :class="triggerClass" :title="triggerTitle">
      <slot name="trigger" />
    </div>
    <ul
      tabindex="0"
      :class="['dropdown-content menu menu-sm bg-base-100 rounded-box z-30 p-2 shadow border border-base-300', menuClass]"
    >
      <slot />
    </ul>
  </div>
</template>

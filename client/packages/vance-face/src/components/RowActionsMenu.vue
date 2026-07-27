<script setup lang="ts">
/**
 * Generic per-row "kebab" (⋯) action menu. Drop it into a list/table row,
 * pass the context-appropriate {@link RowMenuItem}s, and handle {@code select}.
 *
 * The panel is teleported to <body> and positioned fixed against the trigger
 * so it is never clipped by a scroll container or an {@code overflow-hidden}
 * ancestor. Global listeners (outside-click / Escape / scroll / resize) are
 * attached only while the menu is open — a long list of rows therefore costs
 * nothing until one menu is actually opened.
 */
import { onBeforeUnmount, ref } from 'vue';

export interface RowMenuItem {
  key: string;
  label: string;
  /** Render in the error color (destructive action). */
  danger?: boolean;
  disabled?: boolean;
}

withDefaults(
  defineProps<{
    items: RowMenuItem[];
    /** Shown (disabled) when {@link items} is empty. */
    emptyLabel?: string;
    /** Accessible label / tooltip for the trigger. */
    title?: string;
  }>(),
  { emptyLabel: '', title: '' },
);

const emit = defineEmits<{ (e: 'select', key: string): void }>();

const open = ref(false);
const triggerEl = ref<HTMLElement | null>(null);
const menuStyle = ref<Record<string, string>>({});

const MENU_WIDTH_PX = 200;

function position(): void {
  const el = triggerEl.value;
  if (!el) return;
  const rect = el.getBoundingClientRect();
  menuStyle.value = {
    position: 'fixed',
    top: `${rect.bottom + 4}px`,
    left: `${Math.max(8, rect.right - MENU_WIDTH_PX)}px`,
    width: `${MENU_WIDTH_PX}px`,
  };
}

function close(): void {
  if (!open.value) return;
  open.value = false;
  window.removeEventListener('mousedown', onDocMouseDown, true);
  window.removeEventListener('keydown', onKeydown, true);
  window.removeEventListener('scroll', close, true);
  window.removeEventListener('resize', close, true);
}

function toggle(): void {
  if (open.value) {
    close();
    return;
  }
  position();
  open.value = true;
  window.addEventListener('mousedown', onDocMouseDown, true);
  window.addEventListener('keydown', onKeydown, true);
  window.addEventListener('scroll', close, true);
  window.addEventListener('resize', close, true);
}

function onDocMouseDown(e: MouseEvent): void {
  const target = e.target as Node | null;
  if (target && triggerEl.value?.contains(target)) return;
  // Menu items handle their own click (which closes); any other target closes.
  if (target && (target as HTMLElement).closest?.('[data-row-menu-panel]')) return;
  close();
}

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape') close();
}

function choose(item: RowMenuItem): void {
  if (item.disabled) return;
  close();
  emit('select', item.key);
}

onBeforeUnmount(close);
</script>

<template>
  <button
    ref="triggerEl"
    type="button"
    class="px-2 py-1 rounded hover:bg-base-300/70 leading-none text-base opacity-70 hover:opacity-100"
    :title="title"
    aria-haspopup="menu"
    @click.stop="toggle"
  >⋯</button>

  <Teleport to="body">
    <div
      v-if="open"
      data-row-menu-panel
      class="z-50 rounded-lg border border-base-300 bg-base-100 shadow-lg py-1 text-sm"
      :style="menuStyle"
      @click.stop
    >
      <template v-if="items.length > 0">
        <button
          v-for="item in items"
          :key="item.key"
          type="button"
          class="w-full text-left px-3 py-1.5 hover:bg-base-200 disabled:opacity-40 disabled:cursor-not-allowed"
          :class="{ 'text-error': item.danger }"
          :disabled="item.disabled"
          @click="choose(item)"
        >{{ item.label }}</button>
      </template>
      <div v-else class="px-3 py-1.5 opacity-50">{{ emptyLabel }}</div>
    </div>
  </Teleport>
</template>

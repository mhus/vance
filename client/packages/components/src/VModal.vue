<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';

interface Props {
  /** Two-way bound visibility flag. */
  modelValue: boolean;
  title?: string;
  /**
   * Whether clicking on the backdrop closes the modal. Default `true`.
   * Set to `false` for forms with unsaved input that should require an
   * explicit cancel.
   */
  closeOnBackdrop?: boolean;
  /**
   * Max-width preset. Default `md` (max-w-2xl) for forms; use `lg`/`xl`
   * for content-heavy modals like an embedded editor.
   */
  size?: 'md' | 'lg' | 'xl';
}

const props = withDefaults(defineProps<Props>(), {
  closeOnBackdrop: true,
  size: 'md',
});

const sizeClass = computed(() => ({
  md: 'max-w-2xl',
  lg: 'max-w-4xl',
  xl: 'max-w-6xl',
}[props.size]));

// DaisyUI's .modal-box carries a CSS transform (open animation) which
// creates a containing block — that breaks `position: fixed` descendants
// (e.g. the block-editor's drag handle renders offset by the modal's
// position). Content modals (lg/xl), which may embed such editors, drop
// the transform so fixed children resolve against the viewport again.
// Form modals (md) keep the animation.
const boxStyle = computed(() =>
  props.size === 'md' ? undefined : { transform: 'none' },
);

const emit = defineEmits<{ (e: 'update:modelValue', open: boolean): void }>();

const dialog = ref<HTMLDialogElement | null>(null);

watch(() => props.modelValue, (open) => {
  const el = dialog.value;
  if (!el) return;
  if (open && !el.open) el.showModal();
  if (!open && el.open) el.close();
});

// Initial mount case: a watch without `immediate: true` doesn't fire
// on the starting value, so if the modal is conditionally rendered
// (v-if) and lands here with modelValue already `true`, the dialog
// element exists in the DOM but nobody ever called showModal() on it —
// it stays invisible. Cover that one path explicitly. We can't use
// `immediate` on the watch above because the template ref is null
// until after mount.
onMounted(() => {
  const el = dialog.value;
  if (!el) return;
  if (props.modelValue && !el.open) el.showModal();
});

function onClose(): void {
  emit('update:modelValue', false);
}

function onBackdropClick(event: MouseEvent): void {
  if (!props.closeOnBackdrop) return;
  // The dialog element receives clicks on the backdrop when the click target
  // is the dialog itself (not its inner content).
  if (event.target === dialog.value) onClose();
}

onUnmounted(() => {
  if (dialog.value?.open) dialog.value.close();
});
</script>

<template>
  <dialog
    ref="dialog"
    class="modal"
    @close="onClose"
    @click="onBackdropClick"
  >
    <div class="modal-box" :class="sizeClass" :style="boxStyle">
      <header v-if="title || $slots.header" class="flex items-center justify-between mb-3">
        <h3 class="text-lg font-semibold">
          <slot name="header">{{ title }}</slot>
        </h3>
        <button
          type="button"
          class="btn btn-sm btn-circle btn-ghost"
          aria-label="Close"
          @click="onClose"
        >✕</button>
      </header>

      <div>
        <slot />
      </div>

      <footer v-if="$slots.actions" class="modal-action">
        <slot name="actions" />
      </footer>
    </div>
  </dialog>
</template>

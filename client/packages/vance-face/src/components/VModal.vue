<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue';

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
}

const props = withDefaults(defineProps<Props>(), {
  closeOnBackdrop: true,
});

const emit = defineEmits<{ (e: 'update:modelValue', open: boolean): void }>();

const dialog = ref<HTMLDialogElement | null>(null);

watch(() => props.modelValue, (open) => {
  const el = dialog.value;
  if (!el) return;
  if (open && !el.open) el.showModal();
  if (!open && el.open) el.close();
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
    <div class="modal-box max-w-2xl">
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

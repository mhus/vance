<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import VIconButton from './VIconButton.vue';
import { useT } from './useT';

const t = useT();

interface Props {
  /** Two-way bound visibility flag. */
  modelValue: boolean;
  title?: string;
  /**
   * One or two sentences under the title — what the dialog does, when the
   * title alone does not say it (corporate-design.md §6.4).
   */
  description?: string;
  /**
   * Whether clicking on the backdrop closes the modal. Default `true`.
   * Set to `false` for forms with unsaved input that should require an
   * explicit cancel.
   */
  closeOnBackdrop?: boolean;
  /**
   * Width preset (corporate-design.md §6.4): `sm` 440 px for a few fields
   * or a confirmation, `md` 600 px (default) for forms and pick lists,
   * `lg`/`xl` for content-heavy modals like an embedded editor.
   */
  size?: 'sm' | 'md' | 'lg' | 'xl';
  /**
   * A fixed box height, as a CSS length — '80vh', '32rem'. Default:
   * unset, the box sizes to its content.
   *
   * For dialogs whose content changes size between states — a
   * quick-open whose result list comes and goes with every search —
   * so the dialog keeps its geometry instead of breathing with each
   * keystroke. The box becomes a column, the content slot gets the
   * leftover height and is expected to manage its own scrolling.
   */
  height?: string;
}

const props = withDefaults(defineProps<Props>(), {
  closeOnBackdrop: true,
  size: 'md',
});

const sizeClass = computed(() => `v-modal-${props.size}`);

// The box is always a column: header and footer keep their natural size,
// the content between them scrolls once the box reaches the viewport
// height — so the actions never slide out of view. A fixed `height` makes
// the content slot take the leftover space instead of sizing to itself.
const heightStyle = computed(() =>
  props.height ? { height: props.height } : undefined,
);
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
    <div class="modal-box v-modal" :class="sizeClass" :style="[boxStyle, heightStyle]">
      <header v-if="title || $slots.header" class="v-modal-header">
        <div class="v-modal-heading">
          <h3 class="v-modal-title">
            <slot name="header">{{ title }}</slot>
          </h3>
          <p v-if="description" class="v-modal-description">{{ description }}</p>
        </div>
        <VIconButton icon="close" :label="t('common.close')" @click="onClose" />
      </header>

      <div :class="['v-modal-body', { 'v-modal-body-fill': height }]">
        <slot />
      </div>

      <footer v-if="$slots.actions" class="v-modal-actions">
        <slot name="actions" />
      </footer>
    </div>
  </dialog>
</template>

<style scoped>
/* Dialog anatomy (corporate-design.md §6.4): raised surface, radius 14,
   24 px padding; title 17/600 with an optional description; content
   scrolls between a fixed header and a fixed action row. */
.v-modal {
  display: flex;
  flex-direction: column;
  max-height: calc(100dvh - 64px);
  padding: 24px;
  overflow: hidden;
  border-radius: var(--v-radius-modal);
  background: var(--v-surface-raised);
  box-shadow: var(--v-shadow-float);
  color: var(--v-label);
}
.v-modal-sm { max-width: 440px; }
.v-modal-md { max-width: 600px; }
.v-modal-lg { max-width: 56rem; }
.v-modal-xl { max-width: 72rem; }
.v-modal-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
  flex-shrink: 0;
}
.v-modal-heading { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.v-modal-title {
  margin: 0;
  font-size: 17px;
  line-height: 22px;
  font-weight: 600;
}
.v-modal-description {
  margin: 0;
  font-size: 13px;
  line-height: 18px;
  color: var(--v-label-secondary);
}
.v-modal-body {
  min-height: 0;
  overflow-y: auto;
  /* Room for focus rings of fields at the scroll edge. */
  margin: -4px;
  padding: 4px;
}
/* The dialog ground is already white — a VGroup inside needs the hairline
   to stay a group. */
.v-modal-body :deep(.v-group) { box-shadow: inset 0 0 0 1px var(--v-separator); }
.v-modal-body-fill { flex: 1 1 auto; overflow: hidden; }
.v-modal-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  margin-top: 24px;
  flex-shrink: 0;
}
</style>

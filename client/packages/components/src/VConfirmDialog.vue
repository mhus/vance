<script setup lang="ts">
// The confirmation dialog of corporate-design.md §6.4: narrow, the question
// as title, one sentence of consequence, Cancel + the confirming action —
// filled danger when the action destroys something. Rendered by useConfirm(),
// not placed in templates.
import { ref } from 'vue';
import VButton from './VButton.vue';
import VModal from './VModal.vue';
import { useT } from './useT';

interface Props {
  title?: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  danger?: boolean;
}

const props = withDefaults(defineProps<Props>(), { danger: true });

const emit = defineEmits<{ (e: 'resolve', confirmed: boolean): void }>();

const t = useT();
const open = ref(true);
let settled = false;

function settle(confirmed: boolean): void {
  if (settled) return;
  settled = true;
  open.value = false;
  emit('resolve', confirmed);
}

function onOpenChange(value: boolean): void {
  if (!value) settle(false);
}
</script>

<template>
  <VModal
    :model-value="open"
    :title="props.title"
    size="sm"
    @update:model-value="onOpenChange"
  >
    <p class="v-confirm-message">{{ props.message }}</p>
    <template #actions>
      <VButton variant="secondary" @click="settle(false)">
        {{ props.cancelLabel ?? t('common.cancel') }}
      </VButton>
      <VButton
        :variant="props.danger ? 'danger' : 'primary'"
        :filled="props.danger"
        autofocus
        @click="settle(true)"
      >
        {{ props.confirmLabel ?? t('common.confirm') }}
      </VButton>
    </template>
  </VModal>
</template>

<style scoped>
.v-confirm-message {
  margin: 0;
  font-size: 14px;
  line-height: 20px;
  color: var(--v-label);
  white-space: pre-line;
}
</style>

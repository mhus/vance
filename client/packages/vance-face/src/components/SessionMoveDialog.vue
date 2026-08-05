<script setup lang="ts">
import { VAlert, VButton, VModal, VSelect } from '@vance/components';
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useTenantProjects } from '@composables/useTenantProjects';

/**
 * Confirmation dialog for the "Move to project…" session action. Presents
 * the tenant's other projects as targets, forces the user to acknowledge
 * the (deliberate) memory loss + group removal, and emits the chosen
 * target. The move itself runs in the parent (SessionActionsMenu) via
 * {@link useSessionActions}, so {@code saving}/{@code error} are passed in.
 *
 * See planning/session-move.md.
 */
const props = defineProps<{
  modelValue: boolean;
  /** The session's current project — excluded from the target list. */
  currentProjectId: string;
  saving: boolean;
  error: string | null;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', open: boolean): void;
  (e: 'confirm', targetProjectId: string): void;
}>();

const { t } = useI18n();
const { projects, reload } = useTenantProjects();

const selected = ref<string | null>(null);

// Load the project list each time the dialog opens; reset the selection.
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      selected.value = null;
      void reload();
    }
  },
);

// System projects (leading underscore, e.g. _user_… / _tenant) and the
// session's current project are not valid move targets.
const targetOptions = computed(() =>
  projects.value
    .filter((p) => p.name !== props.currentProjectId && !p.name.startsWith('_'))
    .map((p) => ({ value: p.name, label: p.title?.trim() || p.name })),
);

function close(): void {
  emit('update:modelValue', false);
}

function confirm(): void {
  if (!selected.value) return;
  emit('confirm', selected.value);
}
</script>

<template>
  <VModal
    :model-value="modelValue"
    :title="t('chat.sessionHeader.moveTitle')"
    :close-on-backdrop="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="flex flex-col gap-4">
      <VAlert variant="warning">
        {{ t('chat.sessionHeader.moveWarning') }}
      </VAlert>

      <VSelect
        v-if="targetOptions.length > 0"
        v-model="selected"
        :options="targetOptions"
        :label="t('chat.sessionHeader.moveTargetLabel')"
        :placeholder="t('chat.sessionHeader.moveTargetPlaceholder')"
        :disabled="saving"
      />
      <p v-else class="text-sm opacity-70">
        {{ t('chat.sessionHeader.moveNoProjects') }}
      </p>

      <VAlert v-if="error" variant="error">{{ error }}</VAlert>
    </div>

    <template #actions>
      <VButton variant="ghost" :disabled="saving" @click="close">
        {{ t('chat.sessionHeader.moveCancel') }}
      </VButton>
      <VButton
        variant="danger"
        :loading="saving"
        :disabled="saving || !selected"
        @click="confirm"
      >
        {{ t('chat.sessionHeader.moveConfirm') }}
      </VButton>
    </template>
  </VModal>
</template>

<script setup lang="ts">
/**
 * What starts this workflow: the events and schedulers that name it, as chips,
 * and — in build mode — a way to add one and to remove those the editor made.
 * A workflow that does not live under {@code _vance/workflows/} is not found
 * by name, so nothing can point at it; the row says so instead of offering
 * what could not work.
 *
 * Plan: {@code planning/workflow-editor.md}.
 */
import { computed, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VBadge, VButton, VIcon, VIconButton, useConfirm } from '@/components';
import SourceDialog from './SourceDialog.vue';
import { useWorkflowSources } from './useWorkflowSources';
import type { WorkflowTools } from './useWorkflowTools';
import type { WorkflowSource } from './workflowSources';

defineOptions({ name: 'WorkflowSources' });

const props = defineProps<{
  projectId?: string;
  /** The name the workflow is started by; {@code null} when it has none. */
  workflow: string | null;
  /** Sources can be added and removed. */
  editable: boolean;
  tools: WorkflowTools;
}>();

const { t } = useI18n();
const confirmAction = useConfirm();
const store = useWorkflowSources(() => props.projectId, () => props.workflow);

const dialogOpen = ref(false);
const failure = ref<string | null>(null);

const shown = computed(() => store.sources.value);

async function remove(source: WorkflowSource): Promise<void> {
  const confirmed = await confirmAction({
    title: t('documents.workflowView.sources.removeTitle', { name: source.name }),
    message: source.kind === 'scheduler'
      ? t('documents.workflowView.sources.removeScheduler')
      : source.from
        ? t('documents.workflowView.sources.removeEvent')
        : t('documents.workflowView.sources.removeWebhook'),
    confirmLabel: t('documents.workflowView.sources.remove'),
  });
  if (!confirmed) return;
  failure.value = null;
  try {
    await store.remove(source);
  } catch (e) {
    failure.value = e instanceof Error ? e.message : String(e);
  }
}
</script>

<template>
  <div v-if="projectId" class="wf-sources">
    <span class="wf-sources-label">{{ t('documents.workflowView.sources.label') }}</span>

    <span v-if="!workflow" class="wf-sources-note">{{ t('documents.workflowView.sources.notByName') }}</span>
    <template v-else>
      <span
        v-for="source in shown"
        :key="`${source.kind}:${source.name}`"
        class="wf-source-chip"
        :class="{ 'wf-source-chip--off': !source.enabled }"
        :title="source.from ? t('documents.workflowView.sources.madeFrom', { from: source.from }) : undefined"
      >
        <VIcon :name="source.kind === 'scheduler' ? 'calendar' : 'link'" />
        <span class="wf-source-chip-name">{{ source.name }}</span>
        <VBadge variant="neutral">{{ source.summary }}</VBadge>
        <VIconButton
          v-if="editable && source.removable"
          icon="delete"
          :label="t('documents.workflowView.sources.remove')"
          size="sm"
          @click="remove(source)"
        />
      </span>
      <span v-if="shown.length === 0 && !store.loading.value" class="wf-sources-note">
        {{ t('documents.workflowView.sources.none') }}
      </span>
      <VButton v-if="editable" size="sm" variant="secondary" icon="add" @click="dialogOpen = true">
        {{ t('documents.workflowView.sources.add') }}
      </VButton>
    </template>

    <VAlert v-if="failure || store.error.value" variant="error" class="wf-sources-alert">
      {{ failure ?? store.error.value }}
    </VAlert>

    <SourceDialog
      v-if="workflow"
      v-model="dialogOpen"
      :project-id="projectId"
      :workflow="workflow"
      :tools="tools"
      :taken-names="store.takenNames.value"
      @created="store.reload()"
    />
  </div>
</template>

<style scoped>
.wf-sources { display: flex; align-items: center; gap: 0.5rem; flex-wrap: wrap; }
.wf-sources-label { font-size: 0.72rem; text-transform: uppercase; letter-spacing: 0.04em; opacity: 0.6; }
.wf-sources-note { font-size: 0.8rem; opacity: 0.65; }
.wf-sources-alert { flex-basis: 100%; }
.wf-source-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  padding: 0.15rem 0.5rem;
  border: 1px solid color-mix(in oklab, var(--color-base-content) 18%, transparent);
  border-radius: 999px;
  font-size: 0.8rem;
}
.wf-source-chip--off { opacity: 0.55; }
.wf-source-chip-name { font-weight: 600; }
</style>

<script setup lang="ts">
/**
 * What can be added to a workflow: the task types the engine runs itself, and
 * every tool a {@code tool_task} can call, grouped by service with what the
 * service's catalog entry says (title, login, the operations to reach for
 * first). One list, searchable; a pick is reported, nothing is changed here.
 *
 * Plan: {@code planning/workflow-editor.md}.
 */
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { webuiAssetUrl } from '@vance/shared';
import { VAlert, VBadge, VButton, VGroup, VGroupRow, VIcon, VInput, VModal } from '@/components';
import type { IconName } from '@vance/components';
import type { WorkflowTaskType } from '@/workflowGraph/workflowGraphCodec';
import { resolveNodeIcon, type NodeIcon } from '@/workflowGraph/nodeIcon';
import { taskIcon } from '@/workflowGraph/taskIcon';
import { paletteGroups, type PaletteTool } from './paletteModel';
import { TEMPLATE_TYPES } from './stateTemplates';
import type { WorkflowTools } from './useWorkflowTools';

defineOptions({ name: 'WorkflowPalette' });

const props = defineProps<{
  modelValue: boolean;
  /** The tool list, shared with whatever else needs it. */
  tools: WorkflowTools;
  /** The project the logos are looked up in (the asset cascade begins there). */
  projectId?: string;
}>();
const emit = defineEmits<{
  (e: 'update:modelValue', open: boolean): void;
  (e: 'pick-task', type: WorkflowTaskType): void;
  (e: 'pick-tool', tool: PaletteTool): void;
}>();

const { t } = useI18n();

const tools = props.tools;
const query = ref('');

// Fetched when the palette first opens, again on each later open (a login may have been made).
watch(() => props.modelValue, (open) => {
  if (!open) return;
  query.value = '';
  void tools.load();
});

const needle = computed(() => query.value.trim().toLowerCase());

/** The task types, filtered by the query like the tools are. */
const taskTypes = computed(() =>
  TEMPLATE_TYPES.filter(
    (type) => needle.value === ''
      || `${type} ${t(`documents.workflowView.palette.task.${type}`)}`.toLowerCase().includes(needle.value),
  ),
);

const groups = computed(() => paletteGroups(tools.services.value, query.value));
const nothing = computed(
  () => tools.loaded.value && taskTypes.value.length === 0 && groups.value.length === 0,
);

/** The icon of a service, from its catalog entry. */
function serviceIcon(icon: string | undefined): NodeIcon | null {
  return resolveNodeIcon(icon, (path) => webuiAssetUrl(props.projectId, path));
}

function close(): void {
  emit('update:modelValue', false);
}

function pickTask(type: WorkflowTaskType): void {
  emit('pick-task', type);
  close();
}

function pickTool(tool: PaletteTool): void {
  emit('pick-tool', tool);
  close();
}
</script>

<template>
  <VModal
    :model-value="modelValue"
    size="lg"
    :title="t('documents.workflowView.palette.title')"
    :description="t('documents.workflowView.palette.description')"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="wf-palette">
      <VInput
        v-model="query"
        type="text"
        :placeholder="t('documents.workflowView.palette.search')"
      />

      <VGroup v-if="taskTypes.length > 0" :label="t('documents.workflowView.palette.tasks')">
        <VGroupRow
          v-for="type in taskTypes"
          :key="type"
          :label="type"
          :icon="taskIcon(type) ?? undefined"
          :description="t(`documents.workflowView.palette.task.${type}`)"
          navigates
          @click="pickTask(type)"
        />
      </VGroup>

      <VAlert v-if="tools.error.value" variant="error">
        {{ tools.error.value }}
        <VButton size="sm" variant="secondary" @click="tools.load()">
          {{ t('documents.workflowView.palette.retry') }}
        </VButton>
      </VAlert>
      <p v-else-if="tools.busy.value && !tools.loaded.value" class="wf-palette-note">
        {{ t('documents.workflowView.palette.loading') }}
      </p>

      <VGroup v-for="group in groups" :key="group.service" :label="group.title">
        <template v-if="serviceIcon(group.icon)" #label>
          <span class="wf-palette-service">
            <img
              v-if="serviceIcon(group.icon)?.kind === 'image'"
              class="wf-palette-logo"
              :src="(serviceIcon(group.icon) as { src: string }).src"
              alt=""
            />
            <VIcon v-else :name="(serviceIcon(group.icon) as { name: IconName }).name" />
            {{ group.title }}
          </span>
        </template>
        <template v-if="group.needsLogin || group.total > group.tools.length" #note>
          <span v-if="group.needsLogin">{{ t('documents.workflowView.palette.needsLogin') }} </span>
          <span v-if="group.total > group.tools.length">
            {{ t('documents.workflowView.palette.more', { shown: group.tools.length, total: group.total }) }}
          </span>
        </template>
        <VGroupRow
          v-for="tool in group.tools"
          :key="tool.name"
          :label="tool.label"
          icon="task-tool"
          :description="tool.description"
          navigates
          @click="pickTool(tool)"
        >
          <VBadge v-if="tool.params" variant="neutral">{{ t('documents.workflowView.palette.example') }}</VBadge>
        </VGroupRow>
      </VGroup>

      <p v-if="nothing" class="wf-palette-note">{{ t('documents.workflowView.palette.nothing') }}</p>
    </div>
  </VModal>
</template>

<style scoped>
.wf-palette { display: flex; flex-direction: column; gap: 0.75rem; }
.wf-palette-service { display: inline-flex; align-items: center; gap: 0.4rem; }
.wf-palette-logo { width: 14px; height: 14px; object-fit: contain; }
.wf-palette-note { margin: 0; font-size: 0.85rem; opacity: 0.7; }
</style>

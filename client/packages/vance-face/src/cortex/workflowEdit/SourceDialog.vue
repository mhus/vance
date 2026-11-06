<script setup lang="ts">
/**
 * Adds something that starts the workflow: a trigger of a service from the
 * catalog (a message arrives, a push happens — the server makes the event or
 * the polling scheduler and, where the service allows, registers the URL
 * there), or a plain schedule. The dialog asks and reports; the server makes it.
 *
 * Plan: {@code planning/workflow-editor.md}.
 */
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { brainFetch } from '@vance/shared';
import type { CatalogTriggerCreateRequest, CatalogTriggerCreateResponse } from '@vance/generated';
import { VAlert, VButton, VInput, VModal, VSegment, VSelect } from '@/components';
import { useSchedulers } from '@/composables/useSchedulers';
import type { WorkflowTools } from './useWorkflowTools';
import { catalogTriggers, freeSourceName, schedulerYaml } from './workflowSources';

defineOptions({ name: 'SourceDialog' });

const props = defineProps<{
  modelValue: boolean;
  projectId: string;
  /** The workflow's name — what the new source starts. */
  workflow: string;
  tools: WorkflowTools;
  /** Names of existing events and schedulers. */
  takenNames: string[];
}>();
const emit = defineEmits<{
  (e: 'update:modelValue', open: boolean): void;
  /** Something was made; the list of sources is stale. */
  (e: 'created'): void;
}>();

const { t } = useI18n();
const schedulers = useSchedulers();

type Kind = 'service' | 'schedule';
const kind = ref<Kind>('service');
const busy = ref(false);
const error = ref<string | null>(null);
const created = ref<CatalogTriggerCreateResponse | null>(null);

// ── the choice of service trigger ────────────────────────────────

const choices = computed(() => catalogTriggers(props.tools.services.value));
const serviceOptions = computed(() => {
  const seen = new Map<string, string>();
  for (const c of choices.value) seen.set(c.service, c.serviceTitle);
  return [...seen].map(([value, label]) => ({ value, label }));
});

const service = ref<string | null>(null);
const triggerName = ref<string | null>(null);
const sourceName = ref('');
const inputs = ref<Record<string, string>>({});

const triggerOptions = computed(() =>
  choices.value.filter((c) => c.service === service.value).map((c) => ({ value: c.trigger.name, label: c.trigger.name })),
);
const chosen = computed(() =>
  choices.value.find((c) => c.service === service.value && c.trigger.name === triggerName.value),
);

watch(service, (next) => {
  triggerName.value = choices.value.find((c) => c.service === next)?.trigger.name ?? null;
});
watch(chosen, (next) => {
  inputs.value = {};
  sourceName.value = next ? freeSourceName(`${next.service}-${next.trigger.name}`, props.takenNames) : '';
});

// ── the schedule ─────────────────────────────────────────────────

const scheduleName = ref('');
const description = ref('');
const cron = ref('0 0 6 * * *');

// ── opening ──────────────────────────────────────────────────────

watch(() => props.modelValue, (open) => {
  if (!open) return;
  error.value = null;
  created.value = null;
  void props.tools.ensureLoaded().then(() => {
    kind.value = choices.value.length > 0 ? 'service' : 'schedule';
    service.value = serviceOptions.value[0]?.value ?? null;
  });
  scheduleName.value = freeSourceName(`${props.workflow}-schedule`, props.takenNames);
  description.value = t('documents.workflowView.sources.defaultDescription', { workflow: props.workflow });
});

const kindOptions = computed(() => [
  { value: 'service' as const, label: t('documents.workflowView.sources.kindService') },
  { value: 'schedule' as const, label: t('documents.workflowView.sources.kindSchedule') },
]);

const missingInput = computed(
  () => kind.value === 'service'
    && (chosen.value?.trigger.inputs ?? []).some((i) => i.required && !(inputs.value[i.name] ?? '').trim()),
);
const canSubmit = computed(() => {
  if (busy.value) return false;
  if (kind.value === 'schedule') return scheduleName.value.trim() !== '' && cron.value.trim() !== '' && description.value.trim() !== '';
  return chosen.value !== undefined && sourceName.value.trim() !== '' && !missingInput.value;
});

function close(): void {
  emit('update:modelValue', false);
}

async function submit(): Promise<void> {
  if (!canSubmit.value) return;
  busy.value = true;
  error.value = null;
  try {
    if (kind.value === 'schedule') {
      await schedulers.save(
        props.projectId,
        scheduleName.value.trim(),
        schedulerYaml({ description: description.value.trim(), cron: cron.value.trim(), workflow: props.workflow }),
      );
      emit('created');
      close();
      return;
    }
    const c = chosen.value!;
    const body: CatalogTriggerCreateRequest = {
      service: c.service,
      trigger: c.trigger.name,
      workflow: props.workflow,
      name: sourceName.value.trim(),
      inputs: Object.fromEntries(Object.entries(inputs.value).filter(([, v]) => v.trim() !== '')),
    };
    created.value = await brainFetch<CatalogTriggerCreateResponse>(
      'POST',
      `project/${encodeURIComponent(props.projectId)}/workflow-triggers`,
      { body },
    );
    emit('created');
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <VModal
    :model-value="modelValue"
    size="md"
    :title="t('documents.workflowView.sources.dialogTitle', { workflow })"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="wf-source">
      <template v-if="created">
        <VAlert variant="success">
          {{ t('documents.workflowView.sources.created', { name: created.name }) }}
        </VAlert>
        <dl class="wf-source-result">
          <template v-if="created.urlPath">
            <dt>{{ t('documents.workflowView.sources.urlPath') }}</dt>
            <dd><code>{{ created.urlPath }}</code></dd>
          </template>
          <template v-if="created.secretSetting">
            <dt>{{ t('documents.workflowView.sources.secretSetting') }}</dt>
            <dd><code>{{ created.secretSetting }}</code></dd>
          </template>
          <dt>{{ t('documents.workflowView.sources.registered') }}</dt>
          <dd>{{ created.subscribed ? t('documents.workflowView.sources.yes') : t('documents.workflowView.sources.no') }}</dd>
        </dl>
      </template>

      <template v-else>
        <VSegment
          v-if="choices.length > 0"
          v-model="kind"
          :options="kindOptions"
          :aria-label="t('documents.workflowView.sources.kindLabel')"
        />
        <p v-else-if="tools.busy.value" class="wf-source-note">{{ t('documents.workflowView.palette.loading') }}</p>

        <template v-if="kind === 'service' && choices.length > 0">
          <VSelect v-model="service" :options="serviceOptions" :label="t('documents.workflowView.sources.service')" />
          <VSelect v-model="triggerName" :options="triggerOptions" :label="t('documents.workflowView.sources.trigger')" />
          <p v-if="chosen?.trigger.description" class="wf-source-note">{{ chosen.trigger.description }}</p>
          <VInput v-model="sourceName" :label="t('documents.workflowView.sources.name')" />
          <VInput
            v-for="input in chosen?.trigger.inputs ?? []"
            :key="input.name"
            :model-value="inputs[input.name] ?? ''"
            @update:model-value="inputs[input.name] = $event"
            :label="`${input.name}${input.required ? ' *' : ''}`"
            :help="input.description"
          />
        </template>

        <template v-else>
          <VInput v-model="scheduleName" :label="t('documents.workflowView.sources.name')" />
          <VInput v-model="description" :label="t('documents.workflowView.sources.description')" />
          <VInput v-model="cron" :label="t('documents.workflowView.sources.cron')" :help="t('documents.workflowView.sources.cronHelp')" />
        </template>

        <VAlert v-if="error" variant="error">{{ error }}</VAlert>
      </template>
    </div>

    <template #actions>
      <template v-if="created">
        <VButton variant="primary" @click="close">{{ t('documents.workflowView.sources.done') }}</VButton>
      </template>
      <template v-else>
        <VButton variant="secondary" @click="close">{{ t('documents.workflowView.connect.cancel') }}</VButton>
        <VButton variant="primary" :loading="busy" :disabled="!canSubmit" @click="submit">
          {{ t('documents.workflowView.sources.add') }}
        </VButton>
      </template>
    </template>
  </VModal>
</template>

<style scoped>
.wf-source { display: flex; flex-direction: column; gap: 0.75rem; }
.wf-source-note { margin: 0; font-size: 0.82rem; opacity: 0.7; }
.wf-source-result { display: grid; grid-template-columns: max-content 1fr; gap: 0.3rem 1rem; margin: 0; font-size: 0.85rem; }
.wf-source-result dt { opacity: 0.7; }
.wf-source-result dd { margin: 0; word-break: break-all; }
</style>

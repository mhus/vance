<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import type {
  ToolTemplateAppliedStateDto,
  ToolTemplateApplyResultDto,
  ToolTemplateCatalogEntry,
  ToolTemplateDescriptorDto,
} from '@vance/generated';
import {
  EditorShell,
  VAlert,
  VButton,
  VCard,
  VEmptyState,
  VInput,
  VModal,
} from '@/components';
import { useToolTemplates } from '@/composables/useToolTemplates';
import TemplateInputForm from './TemplateInputForm.vue';
import TemplateApplyResult from './TemplateApplyResult.vue';

const state = useToolTemplates();

const search = ref('');
const projectId = ref('_tenant');

const selected = ref<ToolTemplateCatalogEntry | null>(null);
const descriptor = ref<ToolTemplateDescriptorDto | null>(null);
const inputs = ref<Record<string, string>>({});
const modalOpen = ref(false);
const applied = ref<ToolTemplateAppliedStateDto | null>(null);

const applyResult = ref<ToolTemplateApplyResultDto | null>(null);
const applyError = ref<string | null>(null);

onMounted(state.loadCatalog);

/** Group catalog rows by category for the list view; filters by the search box. */
const grouped = computed<Array<{ category: string; entries: ToolTemplateCatalogEntry[] }>>(() => {
  const needle = search.value.trim().toLowerCase();
  const filtered = state.catalog.value.filter((e) => {
    if (!needle) return true;
    const hay = `${e.name} ${e.title ?? ''} ${e.description ?? ''} ${e.category ?? ''}`.toLowerCase();
    return hay.includes(needle);
  });
  const buckets = new Map<string, ToolTemplateCatalogEntry[]>();
  for (const e of filtered) {
    const cat = e.category && e.category.trim() ? e.category : 'other';
    if (!buckets.has(cat)) buckets.set(cat, []);
    buckets.get(cat)!.push(e);
  }
  return Array.from(buckets.entries())
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([category, entries]) => ({
      category,
      entries: entries.sort((a, b) => (a.title ?? a.name).localeCompare(b.title ?? b.name)),
    }));
});

async function openDetail(entry: ToolTemplateCatalogEntry): Promise<void> {
  selected.value = entry;
  descriptor.value = null;
  inputs.value = {};
  applied.value = null;
  applyResult.value = null;
  applyError.value = null;
  modalOpen.value = true;
  try {
    const [desc, prev] = await Promise.all([
      state.describe(entry.name),
      state.loadApplied(entry.name, projectId.value).catch(() => null),
    ]);
    descriptor.value = desc;
    applied.value = prev;
    inputs.value = seedInputs(desc, prev);
  } catch {
    /* state.error.value already carries the message */
  }
}

/**
 * Seeds the form. Precedence per input:
 *   1. Value from the previous apply (PASSWORD is structurally absent).
 *   2. Per-choice defaults for multi-select.
 *   3. Input-level `defaultValue` for everything else.
 */
function seedInputs(
  desc: ToolTemplateDescriptorDto,
  prev: ToolTemplateAppliedStateDto | null,
): Record<string, string> {
  const init: Record<string, string> = {};
  const prevInputs = prev?.inputs ?? {};
  for (const input of desc.inputs ?? []) {
    const isMulti = input.type === 'multi_select' || input.type === 'multiselect';
    const fromPrev = prevInputs[input.name];
    if (fromPrev !== undefined && fromPrev !== null) {
      if (isMulti) {
        // Applied state stores multi-select as a JSON list — re-encode to
        // the JSON-string form the apply payload expects.
        init[input.name] = JSON.stringify(Array.isArray(fromPrev) ? fromPrev : []);
      } else if (typeof fromPrev === 'string') {
        init[input.name] = fromPrev;
      } else {
        init[input.name] = String(fromPrev);
      }
      continue;
    }
    if (isMulti) {
      const defaults = (input.choices ?? []).filter((c) => c.defaultSelected).map((c) => c.value);
      if (defaults.length > 0) init[input.name] = JSON.stringify(defaults);
      continue;
    }
    if (input.defaultValue != null) init[input.name] = input.defaultValue;
  }
  return init;
}

function closeDetail(): void {
  modalOpen.value = false;
  selected.value = null;
  descriptor.value = null;
  inputs.value = {};
  applied.value = null;
  applyResult.value = null;
  applyError.value = null;
}

function onModalToggle(open: boolean): void {
  if (!open) closeDetail();
}

async function onApply(): Promise<void> {
  if (!selected.value || !descriptor.value) return;
  applyError.value = null;
  try {
    applyResult.value = await state.apply(selected.value.name, {
      projectId: projectId.value,
      inputs: inputs.value,
    });
  } catch (e) {
    applyError.value = e instanceof Error ? e.message : 'Apply failed.';
  }
}
</script>

<template>
  <EditorShell :title="$t('toolTemplates.pageTitle')">
    <div class="p-6 flex flex-col gap-4 max-w-4xl">
      <VAlert v-if="state.error.value" variant="error">
        <span>{{ state.error.value }}</span>
      </VAlert>

      <VCard>
        <p class="text-sm opacity-70">{{ $t('toolTemplates.intro') }}</p>
      </VCard>

      <div class="flex items-center gap-3">
        <VInput
          v-model="search"
          :placeholder="$t('toolTemplates.searchPlaceholder')"
          class="flex-1"
        />
        <label class="flex items-center gap-2 text-xs opacity-70">
          <span>{{ $t('toolTemplates.projectIdLabel') }}</span>
          <VInput v-model="projectId" class="w-48 font-mono" />
        </label>
      </div>

      <div v-if="state.loading.value" class="text-sm opacity-60">
        {{ $t('toolTemplates.loading') }}
      </div>

      <VEmptyState
        v-else-if="grouped.length === 0"
        :headline="$t('toolTemplates.empty.headline')"
        :body="$t('toolTemplates.empty.body')"
      />

      <div v-else class="flex flex-col gap-6">
        <section v-for="bucket in grouped" :key="bucket.category" class="flex flex-col gap-2">
          <h2 class="text-xs uppercase tracking-wide opacity-60">
            {{ bucket.category }}
          </h2>
          <VCard
            v-for="entry in bucket.entries"
            :key="entry.name"
            class="cursor-pointer hover:bg-base-200/40"
            @click="openDetail(entry)"
          >
            <div class="flex items-start justify-between gap-3">
              <div class="flex flex-col gap-1 min-w-0">
                <div class="font-medium">{{ entry.title ?? entry.name }}</div>
                <div class="font-mono text-xs opacity-50">{{ entry.name }}</div>
                <div v-if="entry.description" class="text-sm opacity-70">
                  {{ entry.description }}
                </div>
              </div>
              <VButton variant="primary" @click.stop="openDetail(entry)">
                {{ $t('toolTemplates.install') }}
              </VButton>
            </div>
          </VCard>
        </section>
      </div>
    </div>

    <VModal
      :model-value="modalOpen"
      :title="descriptor?.title ?? selected?.title ?? selected?.name ?? ''"
      @update:model-value="onModalToggle"
    >
      <div v-if="state.busy.value && !descriptor" class="text-sm opacity-60">
        {{ $t('toolTemplates.loading') }}
      </div>

      <div v-else-if="descriptor && !applyResult" class="flex flex-col gap-4">
        <VAlert v-if="applied" variant="info">
          <span>
            {{ $t('toolTemplates.previouslyApplied', {
              at: applied.appliedAt,
              by: applied.appliedBy ?? 'unknown',
            }) }}
          </span>
        </VAlert>
        <p v-if="descriptor.description" class="text-sm opacity-70 whitespace-pre-wrap">
          {{ descriptor.description }}
        </p>
        <TemplateInputForm
          v-if="descriptor.inputs && descriptor.inputs.length > 0"
          :inputs="descriptor.inputs"
          v-model="inputs"
        />
        <p v-else class="text-sm opacity-60">{{ $t('toolTemplates.noInputs') }}</p>

        <VAlert v-if="applyError" variant="error">
          <span>{{ applyError }}</span>
        </VAlert>

        <div class="flex justify-end gap-2 pt-2">
          <VButton variant="ghost" @click="closeDetail">
            {{ $t('common.cancel') }}
          </VButton>
          <VButton variant="primary" :loading="state.busy.value" @click="onApply">
            {{ $t('toolTemplates.apply') }}
          </VButton>
        </div>
      </div>

      <TemplateApplyResult
        v-else-if="applyResult"
        :result="applyResult"
        @close="closeDetail"
      />
    </VModal>
  </EditorShell>
</template>

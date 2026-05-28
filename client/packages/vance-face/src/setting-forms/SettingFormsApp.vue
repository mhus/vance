<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  EditorShell,
  SettingFormView,
  VAlert,
  VButton,
  VEmptyState,
  VSelect,
} from '@/components';
import { useTenantProjects } from '@/composables/useTenantProjects';
import { listSettingForms, RestError } from '@vance/shared';
import type { SettingFormSummaryDto } from '@vance/generated';

/** Sentinel project name representing "tenant-wide". */
const TENANT_PROJECT = '_tenant';

const { t } = useI18n();
const tenantProjects = useTenantProjects();

const selectedProject = ref<string>(TENANT_PROJECT);
const listing = ref<SettingFormSummaryDto[]>([]);
const listLoading = ref(false);
const listError = ref<string | null>(null);

const selectedForm = ref<string | null>(null);
/** Bumped after apply/reset to force the active form to reload its cascade values. */
const reloadKey = ref<number>(0);

const projectOptions = computed(() => {
  const list: Array<{ value: string; label: string }> = [
    { value: TENANT_PROJECT, label: t('settingForms.tenantWide') },
  ];
  for (const p of tenantProjects.projects.value) {
    if (p.name === TENANT_PROJECT) continue;
    if (p.name.startsWith('_user_')) continue;
    list.push({
      value: p.name,
      label: (p.title ? p.title + ' ' : '') + '(' + p.name + ')',
    });
  }
  return list;
});

const groupedByCategory = computed(() => {
  const groups = new Map<string, SettingFormSummaryDto[]>();
  for (const f of listing.value) {
    const cat = f.category ?? '';
    if (!groups.has(cat)) groups.set(cat, []);
    groups.get(cat)!.push(f);
  }
  for (const list of groups.values()) {
    list.sort((a, b) => a.title.localeCompare(b.title));
  }
  return [...groups.entries()].sort((a, b) => a[0].localeCompare(b[0]));
});

/** Pass `undefined` to the brain when the user picked "tenant-wide". */
const effectiveProjectId = computed<string | undefined>(() =>
  selectedProject.value === TENANT_PROJECT ? undefined : selectedProject.value,
);

const activeForm = computed<SettingFormSummaryDto | null>(() => {
  if (!selectedForm.value) return null;
  return listing.value.find((f) => f.name === selectedForm.value) ?? null;
});

async function refreshListing(): Promise<void> {
  listLoading.value = true;
  listError.value = null;
  try {
    const res = await listSettingForms(effectiveProjectId.value);
    listing.value = res.forms ?? [];
    // If the previously selected form is no longer in the list, drop it.
    if (selectedForm.value && !listing.value.some((f) => f.name === selectedForm.value)) {
      selectedForm.value = null;
    }
  } catch (err) {
    listError.value = err instanceof RestError ? err.message : String(err);
    listing.value = [];
  } finally {
    listLoading.value = false;
  }
}

function selectForm(name: string): void {
  selectedForm.value = name;
}

function onApplied(): void {
  // Reload listing (a form's clearable/source might change after a write) and
  // bump the reloadKey so the inner view re-fetches its currentValue map.
  reloadKey.value += 1;
  void refreshListing();
}

onMounted(async () => {
  await tenantProjects.reload();
  await refreshListing();
});

watch(selectedProject, () => {
  selectedForm.value = null;
  void refreshListing();
});

const breadcrumbs = computed<string[]>(() => {
  const proj = selectedProject.value === TENANT_PROJECT
    ? t('settingForms.tenantWide')
    : selectedProject.value;
  return activeForm.value ? [proj, activeForm.value.title] : [proj];
});
</script>

<template>
  <EditorShell :title="t('settingForms.title')" :breadcrumbs="breadcrumbs">
    <template #sidebar>
      <div class="flex flex-col gap-3 p-3 min-h-0">
        <VSelect
          v-model="selectedProject"
          :options="projectOptions"
          :label="t('settingForms.projectLabel')"
          size="sm"
        />

        <div class="flex items-center justify-between px-1">
          <div class="text-xs uppercase tracking-wide opacity-60 font-semibold">
            {{ t('settingForms.formsLabel') }}
          </div>
          <VButton
            variant="ghost"
            size="sm"
            :loading="listLoading"
            @click="refreshListing"
          >
            ⟳
          </VButton>
        </div>

        <VAlert v-if="listError" variant="error">{{ listError }}</VAlert>

        <VEmptyState
          v-else-if="!listLoading && listing.length === 0"
          :headline="t('settingForms.emptyHeadline')"
          :body="t('settingForms.emptyBody')"
        />

        <div
          v-for="[cat, group] in groupedByCategory"
          :key="cat"
          class="flex flex-col gap-1"
        >
          <div
            v-if="cat"
            class="text-[10px] uppercase tracking-wide opacity-50 font-semibold px-1 mt-1"
          >
            {{ cat }}
          </div>
          <button
            v-for="f in group"
            :key="f.name"
            type="button"
            class="text-left px-2.5 py-2 text-sm rounded transition-colors"
            :class="{
              'bg-primary/15 hover:bg-primary/20': selectedForm === f.name,
              'bg-base-200 hover:bg-base-300': selectedForm !== f.name,
            }"
            @click="selectForm(f.name)"
          >
            <div class="flex items-center gap-1.5">
              <span class="font-semibold truncate">{{ f.title }}</span>
            </div>
            <div class="text-xs opacity-70 mt-0.5 line-clamp-2">
              {{ f.description }}
            </div>
          </button>
        </div>
      </div>
    </template>

    <template #default>
      <div class="p-4 max-w-3xl">
        <SettingFormView
          v-if="selectedForm"
          :name="selectedForm"
          :project-id="effectiveProjectId"
          :reload-key="reloadKey"
          @applied="onApplied"
        />
        <VEmptyState
          v-else
          :headline="t('settingForms.pickFormHeadline')"
          :body="t('settingForms.pickFormBody')"
        />
      </div>
    </template>
  </EditorShell>
</template>

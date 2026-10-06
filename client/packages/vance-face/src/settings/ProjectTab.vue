<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  VAlert,
  VButton,
  VCard,
  VCheckbox,
  VInput,
  VSelect,
} from '@/components';
import KitAdminSection from './KitAdminSection.vue';
import { useAdminProjects } from '@/composables/useAdminProjects';
import { useScopeSettings } from '@/composables/useScopeSettings';
import { SettingType } from '@vance/generated';
import type { ProjectDto, ProjectGroupSummary } from '@vance/generated';

/**
 * The project tab of the Settings page — the port of the Scopes page's
 * project card: properties (title, enabled, group, read-only facts),
 * the project language overrides, and the kit administration. Same
 * backends (`admin/projects`, the settings REST through
 * `useScopeSettings`, the kit admin surface), same i18n keys
 * (`scopes.project.*`, `scopes.common.*`). Session groups and project
 * copy stay on the Scopes page until the cutover decides their fate.
 */

const props = defineProps<{
  /** Project name (the selected scope). */
  projectName: string;
  /** The tenant's project groups — for the group select. */
  groups: ProjectGroupSummary[];
}>();

const { t, locale } = useI18n();
const projectsState = useAdminProjects();

const banner = ref<string | null>(null);
const form = reactive({
  title: '',
  enabled: true,
  projectGroupId: '' as string,
});

/** Full DTO of the selected project (lifecycle, status, placement facts). */
const project = computed<ProjectDto | null>(() =>
  projectsState.projects.value.find(p => p.name === props.projectName) ?? null);

const isArchivedProject = computed(() => project.value?.status === 'ARCHIVED');

const lifecycleNote = computed(() => {
  switch (project.value?.lifecycleType) {
    case 'PERMANENT':
      return t('scopes.project.lifecyclePermanentNote');
    case 'EPHEMERAL':
      return t('scopes.project.lifecycleEphemeralNote');
    default:
      return '';
  }
});

const groupSelectOptions = computed(() => [
  { value: '', label: t('scopes.common.noGroup') },
  ...props.groups.map(g => ({ value: g.name, label: g.title || g.name })),
]);

watch(
  () => [props.projectName, projectsState.projects.value] as const,
  () => {
    banner.value = null;
    form.title = project.value?.title ?? '';
    form.enabled = project.value?.enabled ?? true;
    form.projectGroupId = project.value?.projectGroupId ?? '';
  },
  { immediate: true },
);

// The tab is only mounted for a project scope, so a plain setup-level
// load is enough — no mount guard needed.
void projectsState.reload();

async function saveProject(): Promise<void> {
  banner.value = null;
  const targetGroup = form.projectGroupId ?? '';
  try {
    await projectsState.update(props.projectName, {
      title: form.title,
      enabled: form.enabled,
      projectGroupId: targetGroup === '' ? undefined : targetGroup,
      clearProjectGroup: targetGroup === '',
    });
    banner.value = t('scopes.project.saved');
  } catch {
    /* projectsState.error surfaces via the card */
  }
}

async function archiveProject(): Promise<void> {
  if (!confirm(t('scopes.project.confirmArchive', { name: props.projectName }))) return;
  try {
    await projectsState.archive(props.projectName);
    banner.value = t('scopes.project.archived');
    // Stay on the project — its data is still there, just status=ARCHIVED.
    await projectsState.reload();
  } catch {
    /* projectsState.error surfaces via the card */
  }
}

// ─── Project-level language pickers ───
//
// Two settings, both surfaced via dedicated dropdowns so users don't
// have to know the key names (chat.language / content.language).
//   "Not set" (empty value) → DELETE the setting → the cascade falls
//     through to the outer layers (then vance.language.default).
//   Any concrete code → upsert as STRING.
// Language names come from Intl.DisplayNames — a translation table would
// be a second place to keep them.

const settingsState = useScopeSettings();

watch(
  () => props.projectName,
  () => { void settingsState.load('project', props.projectName); },
  { immediate: true },
);

const LANGUAGE_KEYS = ['de', 'en', 'fr', 'es', 'it', 'pl'] as const;

const languageOptions = computed(() => {
  let names: Intl.DisplayNames | null = null;
  try {
    names = new Intl.DisplayNames([locale.value || 'en'], { type: 'language' });
  } catch {
    names = null;
  }
  return [
    { value: '', label: t('scopes.project.languageNotSet') },
    ...LANGUAGE_KEYS.map(k => ({
      value: k,
      label: names?.of(k) ?? k.toUpperCase(),
    })),
  ];
});

function settingValueByKey(key: string): string {
  const hit = settingsState.settings.value.find(s => s.key === key);
  return hit?.value ?? '';
}

const projectChatLanguage = computed<string>(() => settingValueByKey('chat.language'));
const projectContentLanguage = computed<string>(() => settingValueByKey('content.language'));

async function setProjectLanguageSetting(key: string, value: string | null): Promise<void> {
  try {
    if (value === null || value === '') {
      // No-op when there's nothing to delete — saves a 404 round-trip and
      // a spurious error in {@link useScopeSettings.remove}.
      if (!settingsState.settings.value.some(s => s.key === key)) return;
      await settingsState.remove('project', props.projectName, key);
    } else {
      await settingsState.upsert(
        'project', props.projectName, key, value, SettingType.STRING, null);
    }
  } catch {
    /* settingsState.error already surfaces via the panel error banner */
  }
}

function onProjectChatLanguageChanged(value: string | null): void {
  void setProjectLanguageSetting('chat.language', value);
}

function onProjectContentLanguageChanged(value: string | null): void {
  void setProjectLanguageSetting('content.language', value);
}
</script>

<template>
  <div class="flex flex-col gap-3">
    <VAlert v-if="projectsState.error.value" variant="error">
      <span>{{ projectsState.error.value }}</span>
    </VAlert>
    <VAlert v-if="banner" variant="success">
      <span>{{ banner }}</span>
    </VAlert>

    <!-- Properties -->
    <VCard :title="t('scopes.project.cardTitle', { name: projectName })">
      <VAlert v-if="isArchivedProject" variant="warning" class="mb-3">
        <span>{{ t('scopes.project.archivedNote') }}</span>
      </VAlert>
      <div v-if="!project" class="opacity-70">{{ t('scopes.loading') }}</div>
      <div v-else class="flex flex-col gap-3">
        <VInput
          :model-value="project.name"
          :label="t('scopes.common.name')"
          disabled
          :help="t('scopes.project.nameImmutable')"
          @update:model-value="() => {}"
        />
        <VInput v-model="form.title" :label="t('scopes.common.title')" />
        <!-- The one place where this state looked like a broken create: the
             project exists, is correct, and simply has nowhere to run yet. -->
        <VAlert v-if="project.placementPendingSince" variant="warning">
          <span>{{ t('scopes.project.placementPendingNote') }}</span>
        </VAlert>
        <VSelect
          v-model="form.projectGroupId"
          :label="t('scopes.project.groupLabel')"
          :options="groupSelectOptions"
        />
        <VCheckbox v-model="form.enabled" :label="t('scopes.common.enabled')" />
        <dl class="grid grid-cols-2 gap-x-4 gap-y-1 text-sm opacity-80">
          <dt class="opacity-60">{{ t('scopes.project.statusLabel') }}</dt>
          <dd>{{ project.status }}</dd>
          <dt class="opacity-60">{{ t('scopes.project.podLabel') }}</dt>
          <dd>{{ project.homeNode ?? t('scopes.common.none') }}</dd>
          <dt class="opacity-60">{{ t('scopes.project.claimedLabel') }}</dt>
          <dd>{{ project.claimedAt ?? t('scopes.common.none') }}</dd>
          <!-- Read-only, and that is the decision, not a gap: what this
               changes is capacity in the cluster, not content in the project,
               so it is written with the operator's token (anus
               `project lifecycle-type`). Shown because the two non-default
               values are facts a tenant has to be able to explain — above
               all EPHEMERAL, which silently stops the project's scheduler. -->
          <dt class="opacity-60">{{ t('scopes.project.lifecycleLabel') }}</dt>
          <dd :class="{ 'text-warning': project.lifecycleType === 'EPHEMERAL' }">
            {{ project.lifecycleType ?? t('scopes.common.none') }}
            <span v-if="lifecycleNote" class="opacity-70">— {{ lifecycleNote }}</span>
          </dd>
          <template v-if="project.placementPendingSince">
            <dt class="opacity-60">{{ t('scopes.project.placementPendingLabel') }}</dt>
            <dd class="text-warning">{{ project.placementPendingSince }}</dd>
          </template>
          <dt class="opacity-60">{{ t('scopes.project.createdLabel') }}</dt>
          <dd>{{ project.createdAt ?? t('scopes.common.none') }}</dd>
        </dl>
        <div class="flex justify-between">
          <VButton
            variant="danger"
            :disabled="isArchivedProject"
            :loading="projectsState.busy.value"
            @click="archiveProject"
          >{{ t('scopes.project.archive') }}</VButton>
          <VButton
            variant="primary"
            :loading="projectsState.busy.value"
            @click="saveProject"
          >{{ t('scopes.common.save') }}</VButton>
        </div>
      </div>
    </VCard>

    <!-- Languages -->
    <VCard :title="t('scopes.project.languagesCardTitle')">
      <p class="text-sm opacity-70 mb-3">
        {{ t('scopes.project.languagesDescription') }}
      </p>
      <div class="flex flex-col gap-3">
        <VSelect
          :model-value="projectChatLanguage"
          :options="languageOptions"
          :label="t('scopes.project.chatLanguageLabel')"
          :disabled="settingsState.busy.value || isArchivedProject"
          @update:model-value="onProjectChatLanguageChanged"
        />
        <p class="text-xs opacity-60 -mt-2">
          {{ t('scopes.project.chatLanguageHelp') }}
        </p>
        <VSelect
          :model-value="projectContentLanguage"
          :options="languageOptions"
          :label="t('scopes.project.contentLanguageLabel')"
          :disabled="settingsState.busy.value || isArchivedProject"
          @update:model-value="onProjectContentLanguageChanged"
        />
        <p class="text-xs opacity-60 -mt-2">
          {{ t('scopes.project.contentLanguageHelp') }}
        </p>
      </div>
    </VCard>

    <!-- Kits -->
    <KitAdminSection :project-name="projectName" />
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  VAlert,
  VButton,
  VCard,
  VCheckbox,
  VEmptyState,
  VInput,
  VModal,
  VSelect,
} from '@/components';
import KitAdminSection from './KitAdminSection.vue';
import { useAdminProjects } from '@/composables/useAdminProjects';
import { useScopeSettings } from '@/composables/useScopeSettings';
import { useSessionGroups } from '@/composables/useSessionGroups';
import { SettingType } from '@vance/generated';
import type {
  ProjectCopyReportDto,
  ProjectDto,
  ProjectGroupSummary,
  SessionGroupDto,
} from '@vance/generated';

/**
 * The project tab of the Settings page — the port of the Scopes page's
 * project pane: properties (title, enabled, group, read-only facts),
 * project copy (dialog with the report), the project language
 * overrides, session-group admin, and the kit administration. Same
 * backends (`admin/projects`, the settings REST through
 * `useScopeSettings`, `admin/session-groups`, the kit admin surface),
 * same i18n keys (`scopes.project.*`, `scopes.sessionGroups.*`,
 * `scopes.common.*`).
 */

const props = defineProps<{
  /** Project name (the selected scope). */
  projectName: string;
  /** The tenant's project groups — for the group select. */
  groups: ProjectGroupSummary[];
}>();

const emit = defineEmits<{
  /** Host navigation: jump to the project a copy report describes. */
  (e: 'open-project', name: string): void;
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

// ─── Project copy ───

const showCopyDialog = ref(false);
const copyReport = ref<ProjectCopyReportDto | null>(null);
const copyForm = reactive({
  name: '',
  title: '',
  projectGroupId: '' as string,
  includeSecrets: false,
});

function openCopyDialog(): void {
  copyReport.value = null;
  projectsState.error.value = null;
  copyForm.name = `${props.projectName}-copy`;
  copyForm.title = project.value?.title ? `${project.value.title} (Copy)` : '';
  copyForm.projectGroupId = project.value?.projectGroupId ?? '';
  copyForm.includeSecrets = false;
  showCopyDialog.value = true;
}

async function submitCopy(): Promise<void> {
  const name = slugifyGroupName(copyForm.name);
  if (!name) return;
  try {
    copyReport.value = await projectsState.copy(props.projectName, {
      name,
      title: copyForm.title.trim() === '' ? undefined : copyForm.title.trim(),
      projectGroupId: copyForm.projectGroupId === '' ? undefined : copyForm.projectGroupId,
      includeSecrets: copyForm.includeSecrets,
    });
    // The dialog stays open on purpose: the report is the point of the
    // operation, and closing on success would hide what was left behind.
  } catch {
    /* projectsState.error */
  }
}

/** Leaves the report behind and jumps to the project it describes. */
function openCopiedProject(): void {
  const name = copyReport.value?.project?.name;
  showCopyDialog.value = false;
  copyReport.value = null;
  if (name) emit('open-project', name);
}

/**
 * Slugify a human-typed group name into the server's identifier shape
 * ({@code ^[a-z0-9][a-z0-9_-]*$}): lowercase, invalid runs → '-', must
 * start with an alphanumeric, no trailing separator. Mirrors the
 * create-project behaviour in {@link ProjectListSidebar}.
 */
function slugifyGroupName(raw: string): string {
  return raw
    .toLowerCase()
    .replace(/[^a-z0-9_-]+/g, '-')
    .replace(/^[^a-z0-9]+/, '')
    .replace(/[-_]+$/, '');
}

// ─── Session groups ───

const sessionGroupsState = useSessionGroups();
const newSessionGroupName = ref('');
const newSessionGroupTitle = ref('');
const editingSessionGroup = ref<string | null>(null);
const editingSessionGroupTitle = ref('');

watch(
  () => props.projectName,
  (name) => {
    editingSessionGroup.value = null;
    editingSessionGroupTitle.value = '';
    void sessionGroupsState.reload(name);
  },
  { immediate: true },
);

async function createSessionGroupAction(): Promise<void> {
  const raw = newSessionGroupName.value.trim();
  const name = slugifyGroupName(raw);
  if (!name) return;
  // If the user's original typing didn't survive slugification and they left
  // the title blank, promote the original spelling to the display title.
  const title = newSessionGroupTitle.value.trim() || (raw !== name ? raw : null);
  try {
    await sessionGroupsState.create(props.projectName, name, title);
    newSessionGroupName.value = '';
    newSessionGroupTitle.value = '';
    banner.value = t('scopes.sessionGroups.created', { name });
  } catch {
    /* sessionGroupsState.error */
  }
}

function startEditSessionGroup(group: SessionGroupDto): void {
  editingSessionGroup.value = group.name;
  editingSessionGroupTitle.value = group.title ?? '';
}

function cancelSessionGroupRename(): void {
  editingSessionGroup.value = null;
  editingSessionGroupTitle.value = '';
}

async function saveSessionGroupRename(name: string): Promise<void> {
  try {
    await sessionGroupsState.rename(
      props.projectName, name, editingSessionGroupTitle.value.trim() || null);
    cancelSessionGroupRename();
    banner.value = t('scopes.sessionGroups.renamed');
  } catch {
    /* sessionGroupsState.error */
  }
}

async function deleteSessionGroupAction(name: string): Promise<void> {
  if (!confirm(t('scopes.sessionGroups.confirmDelete', { name }))) return;
  try {
    await sessionGroupsState.remove(props.projectName, name);
    banner.value = t('scopes.sessionGroups.deleted', { name });
  } catch {
    /* sessionGroupsState.error */
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
          <div class="flex gap-2">
            <VButton
              variant="ghost"
              :loading="projectsState.busy.value"
              @click="openCopyDialog"
            >{{ t('scopes.project.copy.action') }}</VButton>
            <VButton variant="primary" :loading="projectsState.busy.value" @click="saveProject">
              {{ t('scopes.common.save') }}
            </VButton>
          </div>
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

    <!-- Session groups -->
    <VCard :title="t('scopes.sessionGroups.cardTitle')">
      <p class="text-sm opacity-70 mb-3">
        {{ t('scopes.sessionGroups.description') }}
      </p>

      <VEmptyState
        v-if="!sessionGroupsState.loading.value && sessionGroupsState.groups.value.length === 0"
        :headline="t('scopes.sessionGroups.empty')"
      />

      <ul v-else class="flex flex-col divide-y divide-base-300">
        <li
          v-for="g in sessionGroupsState.groups.value"
          :key="g.name"
          class="py-2 flex flex-col gap-1"
        >
          <template v-if="editingSessionGroup === g.name">
            <VInput
              v-model="editingSessionGroupTitle"
              :label="t('scopes.sessionGroups.titleLabel')"
            />
            <div class="flex justify-end gap-2 mt-1">
              <VButton variant="ghost" size="sm" @click="cancelSessionGroupRename">
                {{ t('scopes.common.cancel') }}
              </VButton>
              <VButton
                variant="primary"
                size="sm"
                :loading="sessionGroupsState.busy.value"
                @click="saveSessionGroupRename(g.name)"
              >{{ t('scopes.common.save') }}</VButton>
            </div>
          </template>
          <template v-else>
            <div class="flex items-center justify-between gap-2">
              <div class="min-w-0">
                <div class="text-sm font-semibold truncate">{{ g.title || g.name }}</div>
                <div class="text-xs opacity-60 font-mono truncate">{{ g.name }}</div>
              </div>
              <span class="opacity-60 text-xs whitespace-nowrap">
                {{ t('scopes.sessionGroups.sessionCount', { count: g.sessionIds.length }) }}
              </span>
            </div>
            <div class="flex justify-end gap-2 mt-1">
              <VButton variant="ghost" size="sm" @click="startEditSessionGroup(g)">
                {{ t('scopes.sessionGroups.rename') }}
              </VButton>
              <VButton
                variant="ghost"
                size="sm"
                :loading="sessionGroupsState.busy.value"
                @click="deleteSessionGroupAction(g.name)"
              >{{ t('scopes.sessionGroups.delete') }}</VButton>
            </div>
          </template>
        </li>
      </ul>

      <div class="border-t border-base-300 pt-3 mt-2 flex flex-col gap-2">
        <VInput
          v-model="newSessionGroupName"
          :label="t('scopes.sessionGroups.nameLabel')"
          :help="t('scopes.sessionGroups.nameHint')"
        />
        <VInput
          v-model="newSessionGroupTitle"
          :label="t('scopes.sessionGroups.titleLabel')"
        />
        <VButton
          variant="primary"
          size="sm"
          :disabled="!newSessionGroupName.trim()"
          :loading="sessionGroupsState.busy.value"
          @click="createSessionGroupAction"
        >{{ t('scopes.sessionGroups.add') }}</VButton>
      </div>
    </VCard>

    <!-- Kits -->
    <KitAdminSection :project-name="projectName" />
  </div>

  <!-- Copy dialog: form until the copy ran, report afterwards. Both in
       one dialog so what was left behind is read in the same place it
       was decided. -->
  <VModal
    v-model="showCopyDialog"
    :title="t('scopes.project.copy.title', { name: projectName })"
    :close-on-backdrop="false"
  >
    <div class="flex flex-col gap-3">
      <VAlert v-if="projectsState.error.value" variant="error">
        <span>{{ projectsState.error.value }}</span>
      </VAlert>

      <template v-if="!copyReport">
        <p class="text-sm opacity-80">{{ t('scopes.project.copy.description') }}</p>
        <VInput
          v-model="copyForm.name"
          :label="t('scopes.project.copy.nameLabel')"
          :help="t('scopes.project.copy.nameHelp')"
        />
        <VInput v-model="copyForm.title" :label="t('scopes.common.title')" />
        <VSelect
          v-model="copyForm.projectGroupId"
          :label="t('scopes.project.groupLabel')"
          :options="groupSelectOptions"
        />
        <VCheckbox v-model="copyForm.includeSecrets" :label="t('scopes.project.copy.includeSecrets')" />
        <p class="text-xs opacity-70">{{ t('scopes.project.copy.includeSecretsHelp') }}</p>
        <VAlert variant="info">
          <span>{{ t('scopes.project.copy.notCopiedHint') }}</span>
        </VAlert>
        <div class="flex justify-end gap-2 pt-2">
          <VButton variant="ghost" @click="showCopyDialog = false">
            {{ t('scopes.common.cancel') }}
          </VButton>
          <VButton
            variant="primary"
            :disabled="copyForm.name.trim() === ''"
            :loading="projectsState.busy.value"
            @click="submitCopy"
          >{{ t('scopes.project.copy.submit') }}</VButton>
        </div>
      </template>

      <template v-else>
        <VAlert :variant="copyReport.documentsFailed > 0 ? 'warning' : 'success'">
          <span>{{ t('scopes.project.copy.done', { name: copyReport.project?.name ?? copyForm.name }) }}</span>
        </VAlert>
        <dl class="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
          <dt class="opacity-60">{{ t('scopes.project.copy.documentsCopied') }}</dt>
          <dd>{{ copyReport.documentsCopied }}</dd>
          <dt class="opacity-60">{{ t('scopes.project.copy.documentsExcluded') }}</dt>
          <dd>{{ copyReport.documentsExcluded }}</dd>
          <template v-if="copyReport.documentsFailed > 0">
            <dt class="opacity-60">{{ t('scopes.project.copy.documentsFailed') }}</dt>
            <dd class="text-error">{{ copyReport.documentsFailed }}</dd>
          </template>
          <dt class="opacity-60">{{ t('scopes.project.copy.settingsCopied') }}</dt>
          <dd>{{ copyReport.settingsCopied }}</dd>
          <dt class="opacity-60">{{ t('scopes.project.copy.secretsCopied') }}</dt>
          <dd>{{ copyReport.secretsCopied }}</dd>
        </dl>

        <VAlert v-if="copyReport.statusNote" variant="info">
          <span>{{ copyReport.statusNote }}</span>
        </VAlert>

        <div v-if="copyReport.secretsSkipped.length > 0" class="flex flex-col gap-1">
          <span class="text-sm font-semibold">
            {{ t('scopes.project.copy.secretsSkipped') }}
          </span>
          <p class="text-xs opacity-70">{{ t('scopes.project.copy.secretsSkippedHelp') }}</p>
          <ul class="list-disc pl-5 text-xs font-mono">
            <li v-for="key in copyReport.secretsSkipped" :key="key">{{ key }}</li>
          </ul>
        </div>

        <div v-if="copyReport.failures.length > 0" class="flex flex-col gap-1">
          <span class="text-sm font-semibold text-error">
            {{ t('scopes.project.copy.failures') }}
          </span>
          <ul class="list-disc pl-5 text-xs">
            <li v-for="(line, index) in copyReport.failures" :key="index">{{ line }}</li>
          </ul>
        </div>

        <div class="flex flex-col gap-1">
          <span class="text-sm font-semibold">{{ t('scopes.project.copy.notCopied') }}</span>
          <ul class="list-disc pl-5 text-xs opacity-70">
            <li v-for="(line, index) in copyReport.notCopied" :key="index">{{ line }}</li>
          </ul>
        </div>

        <div class="flex justify-end gap-2 pt-2">
          <VButton variant="ghost" @click="showCopyDialog = false">
            {{ t('scopes.common.close') }}
          </VButton>
          <VButton variant="primary" @click="openCopiedProject">
            {{ t('scopes.project.copy.open') }}
          </VButton>
        </div>
      </template>
    </div>
  </VModal>
</template>

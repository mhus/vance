<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  VAlert,
  VButton,
  VCard,
  VCheckbox,
  VInput,
  VModal,
  VSelect,
} from '@/components';
import { useKitAdmin } from '@/composables/useKitAdmin';
import { useKitSourceProjects } from '@/composables/useKitSourceProjects';
import {
  KitImportMode,
  KitPolicyAction,
} from '@vance/generated';
import type {
  KitConfigDto,
  KitExportRequestDto,
  KitImportRequestDto,
  KitInstalledRecordDto,
  KitLibraryEntryDto,
  KitOriginDto,
  KitSourceProjectDto,
} from '@vance/generated';

/**
 * The project's kit administration — the Settings page's port of the
 * Scopes page's kit card. Same composables (`useKitAdmin`,
 * `useKitSourceProjects`), same REST surface, same i18n keys
 * (`scopes.kit.*` — they outlive the Scopes page, this component keeps
 * them alive), so behaviour is identical by construction. Built fresh
 * instead of extracted: the Strangler rule keeps `ScopesApp.vue` frozen
 * until the cutover, and the duplication dies with that page.
 */

const props = defineProps<{
  /** Project the kits belong to (the selected scope). */
  projectName: string;
}>();

const { t } = useI18n();
const kitState = useKitAdmin();
const kitSourceProjects = useKitSourceProjects();

const banner = ref<string | null>(null);

// ─── Install / Update / Export dialog ───

type KitDialogMode = 'install' | 'update' | 'export';

const showKitDialog = ref(false);
const kitDialogMode = ref<KitDialogMode>('install');

const kitForm = reactive({
  url: '',
  path: '',
  branch: '',
  commit: '',
  token: '',
  vaultPassword: '',
  prune: false,
  keepPasswords: false,
  // On (default) ⇒ install/update — the kit gets an install record and
  // stays updatable. Off ⇒ apply (one-off splat without tracking), used
  // for tunings that should not be managed at all.
  trackInstall: true,
  // Separate, opt-in role: mark this project as the *source* of the kit
  // so it can be edited here and exported. Off by default — the everyday
  // case is installing a kit, not authoring one.
  writeManifest: false,
  commitMessage: '',
});

// Record lookups instead of switches: a missing mode would fail vue-tsc,
// so exhaustiveness stays type-enforced and the computeds always return.
const KIT_DIALOG_TITLES: Record<KitDialogMode, string> = {
  install: 'scopes.kit.dialog.installTitle',
  update: 'scopes.kit.dialog.updateTitle',
  export: 'scopes.kit.dialog.exportTitle',
};
const KIT_DIALOG_SUBMIT_LABELS: Record<KitDialogMode, string> = {
  install: 'scopes.kit.dialog.submitInstall',
  update: 'scopes.kit.dialog.submitUpdate',
  export: 'scopes.kit.dialog.submitExport',
};

const kitDialogTitle = computed(() => t(KIT_DIALOG_TITLES[kitDialogMode.value]));
const kitDialogSubmitLabel = computed(() => t(KIT_DIALOG_SUBMIT_LABELS[kitDialogMode.value]));
const kitNeedsUrl = computed(() => kitDialogMode.value === 'install');

// ─── Library picker ───
//
// Only shown when a library actually answers. A tenant with no libraries
// configured — the common case today — sees the plain url form and no
// hint that something is missing.
const libraryEntries = ref<KitLibraryEntryDto[]>([]);
const libraryLoading = ref(false);

async function loadLibrary(): Promise<void> {
  libraryEntries.value = [];
  libraryLoading.value = true;
  try {
    libraryEntries.value = await kitState.loadLibrary(props.projectName);
  } catch {
    // Not reachable, not configured, not signed in — all mean the same
    // thing here: no picker. The url form is still there.
  } finally {
    libraryLoading.value = false;
  }
}

// ─── Project picker ───
//
// Same shape as the library picker and shown by the same rule. The two
// lists are separate because they answer different questions — a library
// serves released kits this tenant is entitled to, this one lists kits
// being authored in this very install.
const kitProjectOffers = computed(() =>
  kitSourceProjects.projects.value.filter(entry => entry.projectId !== props.projectName));

function openKitDialog(mode: KitDialogMode, origin?: KitOriginDto): void {
  kitDialogMode.value = mode;
  kitForm.url = '';
  kitForm.path = '';
  kitForm.branch = '';
  kitForm.commit = '';
  kitForm.token = '';
  kitForm.vaultPassword = '';
  kitForm.prune = false;
  kitForm.keepPasswords = false;
  kitForm.trackInstall = true;
  kitForm.writeManifest = false;
  kitForm.commitMessage = '';

  // Pre-fill the source: from the kit being updated, or — for export —
  // from the authoring manifest that says what this project is.
  const prefill = origin ?? (mode === 'export' ? kitState.manifest.value?.origin : undefined);
  if (prefill) {
    kitForm.url = prefill.url ?? '';
    kitForm.path = prefill.path ?? '';
    kitForm.branch = prefill.branch ?? '';
  }
  showKitDialog.value = true;
  // Only for install: update and export already know their source.
  if (mode === 'install') {
    void loadLibrary();
    void kitSourceProjects.load();
  }
}

/**
 * Update one installed kit. Goes through the same dialog as any other
 * import because the knobs are the same — a private repo still needs a
 * token, a kit with secrets still needs the vault passphrase — only the
 * source is already known.
 */
function updateInstalledKit(record: KitInstalledRecordDto): void {
  openKitDialog('update', record.origin);
}

/** Fill the source fields from a project row, so nothing has to be typed. */
function pickFromProject(entry: KitSourceProjectDto): void {
  // sourceUrl comes from the server so the `project:` scheme is spelled
  // in exactly one place.
  kitForm.url = entry.sourceUrl;
  kitForm.path = '';
  kitForm.branch = '';
  kitForm.commit = '';
}

function pickFromLibrary(entry: KitLibraryEntryDto): void {
  kitForm.url = entry.sourceUrl;
  // The entry's path already addresses the kit inside its library —
  // vendor and id together. Rebuilding it here would be a second place
  // to get it wrong.
  kitForm.path = entry.path;
  kitForm.branch = '';
  kitForm.commit = '';
}

/**
 * Update every installed kit in one go. Uses the plain path — no token,
 * no vault passphrase — because those differ per kit and cannot be
 * meaningfully asked for once; a kit that needs them is updated singly.
 */
async function updateAllKits(): Promise<void> {
  banner.value = null;
  try {
    const results = await kitState.updateAll(props.projectName, false);
    banner.value = t('scopes.kit.updatedAll_msg', { count: results.length });
  } catch {
    /* error already in kitState.error */
  }
}

/**
 * Where a purchased kit stands: expired, close to it, or neither.
 * Kits without a licence date — everything from git — return null and
 * render nothing at all.
 */
function licenceExpiry(record: KitInstalledRecordDto): 'expired' | 'soon' | null {
  const raw = record.descriptor?.licenseExpiresAt;
  if (!raw) return null;
  const expires = new Date(raw).getTime();
  if (Number.isNaN(expires)) return null;
  const now = Date.now();
  if (expires <= now) return 'expired';
  // Four weeks: long enough to renew without hurry, short enough that
  // the notice still means something when it appears.
  return expires - now <= 28 * 24 * 60 * 60 * 1000 ? 'soon' : null;
}

async function uninstallKit(record: KitInstalledRecordDto): Promise<void> {
  if (!confirm(t('scopes.kit.confirmUninstall', { name: record.kit.name }))) return;
  // Asked separately and defaulting to no: forgetting a kit is cheap to
  // undo, deleting the files it brought is not.
  const prune = confirm(t('scopes.kit.confirmUninstallPrune', { name: record.kit.name }));
  banner.value = null;
  try {
    await kitState.uninstall(props.projectName, record.id, prune);
    banner.value = t('scopes.kit.uninstalled_msg', { name: record.kit.name });
  } catch {
    /* error already in kitState.error */
  }
}

async function promoteKit(record: KitInstalledRecordDto): Promise<void> {
  if (!confirm(t('scopes.kit.confirmPromote', { name: record.kit.name }))) return;
  banner.value = null;
  try {
    await kitState.promote(props.projectName, record.id);
    banner.value = t('scopes.kit.promoted_msg', { name: record.kit.name });
  } catch {
    /* error already in kitState.error */
  }
}

async function submitKitDialog(): Promise<void> {
  const projectId = props.projectName;
  banner.value = null;
  try {
    if (kitDialogMode.value === 'export') {
      const request: KitExportRequestDto = {
        projectId,
        url: kitForm.url || undefined,
        path: kitForm.path || undefined,
        branch: kitForm.branch || undefined,
        token: kitForm.token || undefined,
        vaultPassword: kitForm.vaultPassword || undefined,
        commitMessage: kitForm.commitMessage || undefined,
      };
      await kitState.export(projectId, request);
      banner.value = t('scopes.kit.exported_msg');
    } else {
      const request: KitImportRequestDto = {
        projectId,
        source: {
          url: kitForm.url,
          path: kitForm.path || undefined,
          branch: kitForm.branch || undefined,
          commit: kitForm.commit || undefined,
        },
        token: kitForm.token || undefined,
        vaultPassword: kitForm.vaultPassword || undefined,
        // Real mode is forced server-side via the URL verb; this is just
        // a placeholder so the DTO type is satisfied.
        mode: KitImportMode.INSTALL,
        prune: kitForm.prune,
        keepPasswords: kitForm.keepPasswords,
        writeManifest: kitForm.writeManifest,
        // Empty by definition here: params carries what a provisioning
        // entry asks its source for, and this dialog is the hand-typed
        // install — there is no provisioning entry behind it.
        params: {},
      };
      // trackInstall=false ⇒ the user wants a one-off splat, which is
      // exactly what `apply` does server-side: no record, no diff, no
      // update path.
      if (!kitForm.trackInstall) {
        await kitState.apply(projectId, request);
        banner.value = t('scopes.kit.applied_msg');
      } else if (kitDialogMode.value === 'install') {
        await kitState.install(projectId, request);
        banner.value = t('scopes.kit.installed_msg');
      } else {
        await kitState.update(projectId, request);
        banner.value = t('scopes.kit.updated_msg');
      }
    }
    showKitDialog.value = false;
  } catch {
    /* error already in kitState.error */
  }
}

// ─── Per-kit config (update policy + layer order) ───

const showKitConfigDialog = ref(false);
const kitConfigRecord = ref<KitInstalledRecordDto | null>(null);
const kitConfigForm = reactive({
  sortIndex: '',
  defaultAction: KitPolicyAction.KEEP as KitPolicyAction,
  rules: [] as { namespace: 'document' | 'setting'; pattern: string; action: KitPolicyAction }[],
});

const kitPolicyActionOptions = computed(() => [
  { value: KitPolicyAction.KEEP, label: t('scopes.kit.config.actionKeep') },
  { value: KitPolicyAction.OVERWRITE, label: t('scopes.kit.config.actionOverwrite') },
  { value: KitPolicyAction.IGNORE, label: t('scopes.kit.config.actionIgnore') },
  { value: KitPolicyAction.MERGE, label: t('scopes.kit.config.actionMerge') },
]);

const kitPolicyNamespaceOptions = computed(() => [
  { value: 'document', label: t('scopes.kit.config.namespaceDocument') },
  { value: 'setting', label: t('scopes.kit.config.namespaceSetting') },
]);

async function openKitConfigDialog(record: KitInstalledRecordDto): Promise<void> {
  kitConfigRecord.value = record;
  try {
    const config = await kitState.loadConfig(props.projectName, record.id);
    kitConfigForm.sortIndex = config.sortIndex == null ? '' : String(config.sortIndex);
    kitConfigForm.defaultAction = config.policy?.defaultAction ?? KitPolicyAction.KEEP;
    kitConfigForm.rules = (config.policy?.rules ?? []).map((rule) => ({
      namespace: rule.setting != null ? 'setting' : 'document',
      pattern: rule.setting ?? rule.document ?? '',
      action: rule.action,
    }));
    showKitConfigDialog.value = true;
  } catch {
    /* error already in kitState.error */
  }
}

function addKitPolicyRule(): void {
  kitConfigForm.rules.push({
    namespace: 'document',
    pattern: '',
    action: KitPolicyAction.KEEP,
  });
}

function removeKitPolicyRule(index: number): void {
  kitConfigForm.rules.splice(index, 1);
}

async function submitKitConfig(): Promise<void> {
  if (!kitConfigRecord.value) return;
  const trimmed = kitConfigForm.sortIndex.trim();
  const config: KitConfigDto = {
    sortIndex: trimmed === '' ? undefined : Number(trimmed),
    policy: {
      defaultAction: kitConfigForm.defaultAction,
      // Empty patterns would match nothing and only confuse the reader
      // of the resulting YAML.
      rules: kitConfigForm.rules
        .filter((rule) => rule.pattern.trim() !== '')
        .map((rule) => ({
          document: rule.namespace === 'document' ? rule.pattern.trim() : undefined,
          setting: rule.namespace === 'setting' ? rule.pattern.trim() : undefined,
          action: rule.action,
        })),
    },
  };
  banner.value = null;
  try {
    await kitState.saveConfig(props.projectName, kitConfigRecord.value.id, config);
    showKitConfigDialog.value = false;
    banner.value = t('scopes.kit.config.saved_msg');
  } catch {
    /* error already in kitState.error */
  }
}

// Load whenever the project (scope) changes.
watch(
  () => props.projectName,
  () => {
    banner.value = null;
    void kitState.load(props.projectName);
  },
  { immediate: true },
);
</script>

<template>
  <VCard :title="t('scopes.kit.cardTitle')">
    <VAlert v-if="banner" variant="success" class="mb-3">
      <span>{{ banner }}</span>
    </VAlert>
    <VAlert v-if="kitState.error.value" variant="error" class="mb-3">
      <span>{{ kitState.error.value }}</span>
    </VAlert>

    <div v-if="kitState.loading.value" class="opacity-70 text-sm">
      {{ t('scopes.kit.loading') }}
    </div>
    <div v-else class="flex flex-col gap-3 text-sm">
      <div v-if="kitState.installed.value.length === 0" class="opacity-70">
        {{ t('scopes.kit.none') }}
      </div>
      <div
        v-for="record in kitState.installed.value"
        :key="record.id"
        class="flex flex-col gap-1 border-b border-base-300 pb-2 last:border-b-0"
      >
        <div class="flex items-baseline justify-between gap-2">
          <span class="font-semibold">{{ record.kit.name }}</span>
          <div class="flex items-center gap-2 shrink-0">
            <span
              v-if="record.signatureStatus === 'VERIFIED'"
              class="text-xs opacity-70"
              :title="t('scopes.kit.signature.verifiedHelp')"
            >✓ {{ t('scopes.kit.signature.verified') }}</span>
            <!-- Only FAILED is called out. `unsigned` is the normal state
                 for kits from git and flagging it would train people to
                 ignore the badge. -->
            <span
              v-else-if="record.signatureStatus === 'FAILED'"
              class="text-xs text-warning"
              :title="t('scopes.kit.signature.failedHelp')"
            >⚠ {{ t('scopes.kit.signature.failed') }}</span>
            <span v-if="record.kit.version" class="opacity-60 text-xs">
              {{ t('scopes.kit.versionPrefix', { version: record.kit.version }) }}
            </span>
          </div>
        </div>
        <!-- An expired licence stops updates; it does not take anything
             away, so this is a note and not an error. -->
        <VAlert
          v-if="licenceExpiry(record) === 'expired'"
          variant="warning"
          class="text-xs"
        >
          <span>{{ t('scopes.kit.licenceExpired') }}</span>
        </VAlert>
        <div
          v-else-if="licenceExpiry(record) === 'soon'"
          class="text-xs opacity-70"
        >{{ t('scopes.kit.licenceExpiringSoon', {
            date: record.descriptor?.licenseExpiresAt }) }}</div>
        <div v-if="record.kit.description" class="opacity-80">{{ record.kit.description }}</div>
        <dl class="grid grid-cols-2 gap-x-4 gap-y-1 text-xs opacity-80">
          <dt v-if="record.descriptor?.vendor" class="opacity-60">
            {{ t('scopes.kit.vendor') }}
          </dt>
          <dd v-if="record.descriptor?.vendor">{{ record.descriptor.vendor }}</dd>
          <dt v-if="record.descriptor?.license" class="opacity-60">
            {{ t('scopes.kit.license') }}
          </dt>
          <dd v-if="record.descriptor?.license">{{ record.descriptor.license }}</dd>
          <!-- Only shown for purchased kits; a git kit has none of this. -->
          <dt v-if="record.descriptor?.licensedTo" class="opacity-60">
            {{ t('scopes.kit.licensedTo') }}
          </dt>
          <dd v-if="record.descriptor?.licensedTo">{{ record.descriptor.licensedTo }}</dd>
          <dt v-if="record.descriptor?.licenseExpiresAt" class="opacity-60">
            {{ t('scopes.kit.licenseExpires') }}
          </dt>
          <dd v-if="record.descriptor?.licenseExpiresAt">
            {{ record.descriptor.licenseExpiresAt }}
          </dd>
          <dt class="opacity-60">{{ t('scopes.kit.origin') }}</dt>
          <dd class="break-all font-mono">{{ record.origin.url }}</dd>
          <dt v-if="record.origin.path" class="opacity-60">{{ t('scopes.kit.path') }}</dt>
          <dd v-if="record.origin.path">{{ record.origin.path }}</dd>
          <dt v-if="record.origin.branch" class="opacity-60">{{ t('scopes.kit.branch') }}</dt>
          <dd v-if="record.origin.branch">{{ record.origin.branch }}</dd>
          <dt v-if="record.origin.commit" class="opacity-60">{{ t('scopes.kit.commit') }}</dt>
          <dd v-if="record.origin.commit" class="font-mono">
            {{ record.origin.commit.slice(0, 12) }}
          </dd>
          <dt v-if="record.origin.installedAt" class="opacity-60">
            {{ t('scopes.kit.installed') }}
          </dt>
          <dd v-if="record.origin.installedAt">{{ record.origin.installedAt }}</dd>
          <dt class="opacity-60">{{ t('scopes.kit.documents') }}</dt>
          <dd>{{ record.artefacts?.documents?.length ?? 0 }}</dd>
          <dt class="opacity-60">{{ t('scopes.kit.settings') }}</dt>
          <dd>{{ record.artefacts?.settings?.length ?? 0 }}</dd>
          <dt v-if="(record.descriptor?.inherits?.length ?? 0) > 0" class="opacity-60">
            {{ t('scopes.kit.inherits') }}
          </dt>
          <dd v-if="(record.descriptor?.inherits?.length ?? 0) > 0">
            {{ record.descriptor?.inherits?.length ?? 0 }}
          </dd>
        </dl>
        <div class="flex flex-wrap justify-end gap-2 pt-1">
          <VButton
            variant="ghost"
            size="sm"
            @click="openKitConfigDialog(record)"
          >{{ t('scopes.kit.configure') }}</VButton>
          <!-- Promoting is only offered while the project is not
               already some other kit's source — it can only be one. -->
          <VButton
            v-if="!kitState.manifest.value && !record.descriptor?.artifact"
            variant="ghost"
            size="sm"
            :loading="kitState.busy.value"
            @click="promoteKit(record)"
          >{{ t('scopes.kit.promote') }}</VButton>
          <VButton
            variant="ghost"
            size="sm"
            :loading="kitState.busy.value"
            @click="uninstallKit(record)"
          >{{ t('scopes.kit.uninstall') }}</VButton>
          <VButton
            variant="ghost"
            size="sm"
            :loading="kitState.busy.value"
            @click="updateInstalledKit(record)"
          >{{ t('scopes.kit.update') }}</VButton>
        </div>
      </div>

      <!-- Being a kit *source* is a separate, opt-in role — only shown
           when someone actually turned it on. -->
      <div
        v-if="kitState.manifest.value"
        class="border-t border-base-300 pt-2 text-xs opacity-80"
      >
        <div class="font-semibold opacity-90">
          {{ t('scopes.kit.isSource', { name: kitState.manifest.value.kit.name }) }}
        </div>
        <div class="flex flex-wrap justify-end gap-2 pt-2">
          <VButton variant="ghost" size="sm" @click="openKitDialog('export')">
            {{ t('scopes.kit.export') }}
          </VButton>
        </div>
      </div>

      <div class="flex flex-wrap justify-end gap-2 pt-1">
        <VButton
          v-if="kitState.installed.value.length > 1"
          variant="ghost"
          size="sm"
          :loading="kitState.busy.value"
          @click="updateAllKits"
        >{{ t('scopes.kit.updateAll') }}</VButton>
        <VButton
          variant="primary"
          size="sm"
          :loading="kitState.busy.value"
          @click="openKitDialog('install')"
        >{{ t('scopes.kit.install') }}</VButton>
      </div>
    </div>
    <div
      v-if="kitState.lastResult.value"
      class="mt-3 border-t border-base-300 pt-2 text-xs opacity-80"
    >
      <div class="font-semibold opacity-90 mb-1">
        {{ kitState.lastResult.value.version
          ? t('scopes.kit.lastOperationVersion', {
              mode: kitState.lastResult.value.mode,
              version: kitState.lastResult.value.version,
            })
          : t('scopes.kit.lastOperation', { mode: kitState.lastResult.value.mode }) }}
      </div>
      <ul class="flex flex-col gap-0.5">
        <li v-if="(kitState.lastResult.value.documentsAdded?.length ?? 0) > 0">
          {{ t('scopes.kit.docsAdded', { count: kitState.lastResult.value.documentsAdded.length }) }}
        </li>
        <li v-if="(kitState.lastResult.value.documentsUpdated?.length ?? 0) > 0">
          {{ t('scopes.kit.docsUpdated', { count: kitState.lastResult.value.documentsUpdated.length }) }}
        </li>
        <li v-if="(kitState.lastResult.value.documentsRemoved?.length ?? 0) > 0">
          {{ t('scopes.kit.docsRemoved', { count: kitState.lastResult.value.documentsRemoved.length }) }}
        </li>
        <li v-if="(kitState.lastResult.value.settingsAdded?.length ?? 0) > 0
              || (kitState.lastResult.value.settingsUpdated?.length ?? 0) > 0">
          {{ t('scopes.kit.settingsTouched', {
            count: (kitState.lastResult.value.settingsAdded?.length ?? 0)
              + (kitState.lastResult.value.settingsUpdated?.length ?? 0),
          }) }}
        </li>
        <li v-if="(kitState.lastResult.value.toolsAdded?.length ?? 0) > 0
              || (kitState.lastResult.value.toolsUpdated?.length ?? 0) > 0">
          {{ t('scopes.kit.toolsTouched', {
            count: (kitState.lastResult.value.toolsAdded?.length ?? 0)
              + (kitState.lastResult.value.toolsUpdated?.length ?? 0),
          }) }}
        </li>
        <li v-if="(kitState.lastResult.value.skippedPasswords?.length ?? 0) > 0" class="opacity-90">
          {{ t('scopes.kit.passwordsSkipped', {
            count: kitState.lastResult.value.skippedPasswords.length }) }}
        </li>
        <li
          v-for="(w, i) in (kitState.lastResult.value.warnings ?? [])"
          :key="'kw-' + i"
          class="opacity-90"
        >⚠ {{ w }}</li>
      </ul>
    </div>
  </VCard>

  <!-- ─── Install / Update / Export dialog ─── -->
  <VModal v-model="showKitDialog" :title="kitDialogTitle" :close-on-backdrop="false">
    <div class="flex flex-col gap-3">
      <VAlert v-if="kitState.error.value" variant="error">
        <span>{{ kitState.error.value }}</span>
      </VAlert>
      <!-- Project picker: kits authored in this install. Same
           only-when-non-empty rule as the library block below. -->
      <div
        v-if="kitDialogMode === 'install' && kitProjectOffers.length > 0"
        class="flex flex-col gap-2 border border-base-300 rounded p-3"
      >
        <div class="text-sm font-semibold">{{ t('scopes.kit.projects.title') }}</div>
        <p class="text-xs opacity-70">{{ t('scopes.kit.projects.description') }}</p>
        <div
          v-for="entry in kitProjectOffers"
          :key="entry.projectId"
          class="flex items-center gap-2 text-sm"
        >
          <div class="flex-1 min-w-0">
            <div class="flex items-baseline gap-2">
              <span class="font-medium truncate">{{ entry.kitName }}</span>
              <span v-if="entry.version" class="text-xs opacity-60">
                {{ t('scopes.kit.versionPrefix', { version: entry.version }) }}
              </span>
            </div>
            <div class="text-xs opacity-60 truncate">
              {{ entry.projectTitle
                ? `${entry.projectTitle} (${entry.projectId})` : entry.projectId }}
            </div>
          </div>
          <VButton variant="ghost" size="sm" @click="pickFromProject(entry)">
            {{ t('scopes.kit.projects.choose') }}
          </VButton>
        </div>
      </div>

      <!-- Library picker: only rendered when a library answered. With
           none configured there is nothing to show and nothing to
           explain. -->
      <div
        v-if="kitDialogMode === 'install' && (libraryLoading || libraryEntries.length > 0)"
        class="flex flex-col gap-2 border border-base-300 rounded p-3"
      >
        <div class="text-sm font-semibold">{{ t('scopes.kit.library.title') }}</div>
        <div v-if="libraryLoading" class="text-xs opacity-70">
          {{ t('scopes.kit.library.loading') }}
        </div>
        <div
          v-for="entry in libraryEntries"
          :key="entry.sourceId + '/' + entry.kitId"
          class="flex items-center gap-2 text-sm"
        >
          <div class="flex-1 min-w-0">
            <div class="flex items-baseline gap-2">
              <span class="font-medium truncate">{{ entry.displayName }}</span>
              <span v-if="entry.version" class="text-xs opacity-60">
                {{ t('scopes.kit.versionPrefix', { version: entry.version }) }}
              </span>
              <span v-if="entry.installed" class="text-xs opacity-60">
                {{ t('scopes.kit.library.alreadyInstalled') }}
              </span>
            </div>
            <div v-if="entry.vendor || entry.license" class="text-xs opacity-60 truncate">
              {{ [entry.vendor, entry.license].filter(Boolean).join(' · ') }}
            </div>
          </div>
          <!-- Owned but not deliverable stays visible and disabled: hiding
               it would look like the entitlement vanished. -->
          <VButton
            variant="ghost"
            size="sm"
            :disabled="!entry.downloadable"
            :title="entry.downloadable
              ? undefined : t('scopes.kit.library.notDeliverable')"
            @click="pickFromLibrary(entry)"
          >{{ t('scopes.kit.library.choose') }}</VButton>
        </div>
      </div>

      <VInput
        v-model="kitForm.url"
        :label="t('scopes.kit.dialog.repoUrl')"
        :required="kitNeedsUrl"
        :help="kitDialogMode === 'update' || kitDialogMode === 'export'
          ? t('scopes.kit.dialog.repoUrlReuseHelp')
          : t('scopes.kit.dialog.repoUrlHelp')"
      />
      <div class="grid grid-cols-2 gap-3">
        <VInput
          v-model="kitForm.path"
          :label="t('scopes.kit.dialog.subPath')"
          :help="t('scopes.kit.dialog.subPathHelp')"
        />
        <VInput
          v-model="kitForm.branch"
          :label="t('scopes.kit.dialog.branchLabel')"
          :help="t('scopes.kit.dialog.branchHelp')"
        />
      </div>
      <VInput
        v-if="kitDialogMode !== 'export'"
        v-model="kitForm.commit"
        :label="t('scopes.kit.dialog.commitSha')"
        :help="t('scopes.kit.dialog.commitShaHelp')"
      />
      <VInput
        v-model="kitForm.token"
        type="password"
        :label="t('scopes.kit.dialog.authToken')"
        :help="t('scopes.kit.dialog.authTokenHelp')"
      />
      <VInput
        v-model="kitForm.vaultPassword"
        type="password"
        :label="t('scopes.kit.dialog.vaultPassword')"
        :help="kitDialogMode === 'export'
          ? t('scopes.kit.dialog.vaultPasswordExportHelp')
          : t('scopes.kit.dialog.vaultPasswordImportHelp')"
      />
      <VInput
        v-if="kitDialogMode === 'export'"
        v-model="kitForm.commitMessage"
        :label="t('scopes.kit.dialog.commitMessage')"
        :help="t('scopes.kit.dialog.commitMessageHelp')"
      />
      <VCheckbox
        v-if="kitDialogMode !== 'export'"
        v-model="kitForm.trackInstall"
        :label="t('scopes.kit.dialog.trackInstall')"
        :help="t('scopes.kit.dialog.trackInstallHelp')"
      />
      <VCheckbox
        v-if="kitDialogMode !== 'export' && kitForm.trackInstall"
        v-model="kitForm.writeManifest"
        :label="t('scopes.kit.dialog.writeManifest')"
        :help="t('scopes.kit.dialog.writeManifestHelp')"
      />
      <VCheckbox
        v-if="kitDialogMode === 'update' && kitForm.trackInstall"
        v-model="kitForm.prune"
        :label="t('scopes.kit.dialog.prune')"
        :help="t('scopes.kit.dialog.pruneHelp')"
      />
      <VCheckbox
        v-if="kitDialogMode !== 'export' && !kitForm.trackInstall"
        v-model="kitForm.keepPasswords"
        :label="t('scopes.kit.dialog.keepPasswords')"
        :help="t('scopes.kit.dialog.keepPasswordsHelp')"
      />
      <div class="flex justify-end gap-2 pt-2">
        <VButton variant="ghost" @click="showKitDialog = false">
          {{ t('scopes.common.cancel') }}
        </VButton>
        <VButton
          variant="primary"
          :disabled="kitNeedsUrl && !kitForm.url.trim()"
          :loading="kitState.busy.value"
          @click="submitKitDialog"
        >{{ kitDialogSubmitLabel }}</VButton>
      </div>
    </div>
  </VModal>

  <!-- ─── Per-kit config dialog ─── -->
  <VModal
    v-model="showKitConfigDialog"
    :title="t('scopes.kit.config.title', { name: kitConfigRecord?.kit.name ?? '' })"
    :close-on-backdrop="false"
  >
    <div class="flex flex-col gap-3">
      <VAlert v-if="kitState.error.value" variant="error">
        <span>{{ kitState.error.value }}</span>
      </VAlert>
      <VSelect
        v-model="kitConfigForm.defaultAction"
        :label="t('scopes.kit.config.defaultAction')"
        :help="t('scopes.kit.config.defaultActionHelp')"
        :options="kitPolicyActionOptions"
      />
      <VInput
        v-model="kitConfigForm.sortIndex"
        type="number"
        :label="t('scopes.kit.config.sortIndex')"
        :help="t('scopes.kit.config.sortIndexHelp')"
      />

      <div class="flex flex-col gap-2">
        <div class="flex items-baseline justify-between">
          <span class="text-sm font-semibold">{{ t('scopes.kit.config.rules') }}</span>
          <VButton variant="ghost" size="sm" @click="addKitPolicyRule">
            {{ t('scopes.kit.config.addRule') }}
          </VButton>
        </div>
        <p class="text-xs opacity-70">{{ t('scopes.kit.config.rulesHelp') }}</p>
        <div v-if="kitConfigForm.rules.length === 0" class="text-xs opacity-60">
          {{ t('scopes.kit.config.noRules') }}
        </div>
        <div
          v-for="(rule, index) in kitConfigForm.rules"
          :key="index"
          class="flex flex-wrap items-end gap-2"
        >
          <!-- Width goes on a wrapper: VSelect/VInput carry `w-full`
               themselves, and a merged `w-40` loses at equal specificity. -->
          <div class="w-40">
            <VSelect
              v-model="rule.namespace"
              :label="t('scopes.kit.config.namespace')"
              :options="kitPolicyNamespaceOptions"
            />
          </div>
          <div class="flex-1 min-w-48">
            <VInput
              v-model="rule.pattern"
              :label="t('scopes.kit.config.pattern')"
              :placeholder="rule.namespace === 'setting' ? 'ai.alias.*' : 'recipes/*.yaml'"
            />
          </div>
          <div class="w-40">
            <VSelect
              v-model="rule.action"
              :label="t('scopes.kit.config.action')"
              :options="kitPolicyActionOptions"
            />
          </div>
          <VButton variant="ghost" size="sm" @click="removeKitPolicyRule(index)">
            {{ t('scopes.common.delete') }}
          </VButton>
        </div>
      </div>

      <div class="flex justify-end gap-2 pt-2">
        <VButton variant="ghost" @click="showKitConfigDialog = false">
          {{ t('scopes.common.cancel') }}
        </VButton>
        <VButton
          variant="primary"
          :loading="kitState.busy.value"
          @click="submitKitConfig"
        >{{ t('scopes.common.save') }}</VButton>
      </div>
    </div>
  </VModal>
</template>

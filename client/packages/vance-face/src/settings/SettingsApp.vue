<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  EditorShell,
  ProjectListSidebar,
  SettingFormView,
  VAlert,
  VButton,
  VEmptyState,
} from '@/components';
import RawSettingsPanel from './RawSettingsPanel.vue';
import { useProfile } from '@/composables/useProfile';
import { useTenantProjects } from '@/composables/useTenantProjects';
import { recallProject, rememberProject } from '@/platform/lastProject';
import { getTenantId, listSettingForms, RestError } from '@vance/shared';
import { listSettingsKinds, type SettingsDocRow, type SettingsScope } from '@vance/kind-registry';
import type { SettingFormSummaryDto } from '@vance/generated';
import {
  parseSettingsView,
  pushSettingsView,
  replaceSettingsView,
  scopeKeyword,
  TENANT_SCOPE,
  USER_SCOPE,
  type SettingsView,
} from './settingsUrl';

/**
 * The Settings surface — one guided place for everything configurable in a
 * scope: Setting Forms, settings-doc Kinds, and the advanced raw editor.
 * See {@code specification/public/settings-panel.md} and
 * {@code planning/settings-panel.md} (Strangler: the Scopes page stays
 * untouched until the cutover; this page reuses the same backends).
 *
 * <p>Navigation follows the Cortex contract: the URL is the state
 * ({@link ./settingsUrl.ts}). Scope selection, the open form and the tab
 * live in the query; back/forward and reload reproduce the view.
 */

const { t } = useI18n();
const tenantProjects = useTenantProjects();
const profile = useProfile();

/** Tenant project name backing the tenant scope (settings-system.md §3). */
const TENANT_PROJECT = '_tenant';

const view = ref<SettingsView>({ scope: TENANT_SCOPE, form: null, tab: 'guided' });

// ─── Forms listing (guided source #1) ───

const forms = ref<SettingFormSummaryDto[]>([]);
const formsLoading = ref(false);
const formsError = ref<string | null>(null);
/** Bumped after apply/reset so an open form re-fetches its cascade values. */
const formReloadKey = ref(0);

// ─── Settings-doc kinds (guided source #2) ───

const docRows = ref<SettingsDocRow[]>([]);
const docsLoading = ref(false);

// ─── Scope plumbing ───

/** Current user login — the user-scope wire referenceId. */
const login = computed<string>(() => profile.profile.value?.name ?? '');

/** The user project backing the user scope. */
const userProjectId = computed<string>(() => `_user_${login.value}`);

/** Wire scope descriptor the backends are called with. */
const scopeInfo = computed<{ kind: 'tenant' | 'user' | 'project'; projectId?: string; referenceId: string }>(() => {
  const kw = scopeKeyword(view.value.scope);
  if (kw === 'tenant') {
    return { kind: 'tenant', projectId: TENANT_PROJECT, referenceId: getTenantId() ?? '' };
  }
  if (kw === 'user') {
    return { kind: 'user', projectId: userProjectId.value, referenceId: login.value };
  }
  return { kind: 'project', projectId: view.value.scope, referenceId: view.value.scope };
});

/** The projectId for the Setting-Forms listing (undefined ⇒ tenant context). */
const formsProjectId = computed<string | undefined>(() => {
  const kw = scopeKeyword(view.value.scope);
  if (kw === 'tenant') return undefined;
  return scopeInfo.value.projectId;
});

/** The registry scope the settings-doc providers are asked with. */
const registryScope = computed<SettingsScope>(() => ({
  kind: scopeInfo.value.kind,
  projectId: scopeInfo.value.projectId ?? TENANT_PROJECT,
  login: login.value || undefined,
}));

const activeForm = computed<SettingFormSummaryDto | null>(() => {
  if (!view.value.form) return null;
  return forms.value.find((f) => f.name === view.value.form) ?? null;
});

/** Breadcrumbs: scope label, then the open form title. */
const breadcrumbs = computed<string[]>(() => [scopeLabel.value, ...(activeForm.value ? [activeForm.value.title] : [])]);

/** Projects selectable in the sidebar — the hub project is not a settings scope. */
const selectableProjects = computed(() =>
  tenantProjects.projects.value.filter((p) => !p.name.startsWith('_')));

const scopeLabel = computed<string>(() => {
  const kw = scopeKeyword(view.value.scope);
  if (kw === 'tenant') return t('settings.scope.tenant');
  if (kw === 'user') return t('settings.scope.user');
  const p = selectableProjects.value.find((x) => x.name === view.value.scope);
  return p ? (p.title || p.name) : view.value.scope;
});

/**
 * Sidebar selection for the shared ProjectListSidebar. The write side
 * turns a sidebar click into a scope navigation — ProjectListSidebar owns
 * no notion of scope keywords, it only knows project names.
 */
const sidebarProject = computed<string | null>({
  get: () => (scopeKeyword(view.value.scope) ? null : view.value.scope),
  set: (name) => {
    if (name && name !== view.value.scope) selectScope(name);
  },
});

// ─── Loaders ───

async function loadForms(): Promise<void> {
  formsLoading.value = true;
  formsError.value = null;
  try {
    const res = await listSettingForms(formsProjectId.value);
    forms.value = res.forms ?? [];
    // A remembered form that is not in this scope's listing is stale —
    // drop it from the view instead of rendering a dead panel.
    if (view.value.form && !forms.value.some((f) => f.name === view.value.form)) {
      view.value = { ...view.value, form: null };
      replaceSettingsView(view.value);
    }
  } catch (err) {
    formsError.value = err instanceof RestError ? err.message : String(err);
    forms.value = [];
  } finally {
    formsLoading.value = false;
  }
}

async function loadDocRows(): Promise<void> {
  docsLoading.value = true;
  const scope = registryScope.value;
  const settled = await Promise.allSettled(
    listSettingsKinds().map((entry) => entry.settingsProvider!.list(scope)),
  );
  docRows.value = settled.flatMap((r) => (r.status === 'fulfilled' ? r.value : []));
  docsLoading.value = false;
}

async function loadForScope(): Promise<void> {
  await Promise.all([loadForms(), loadDocRows()]);
}

// ─── View transitions (each one pushes history) ───

function navigate(next: SettingsView): void {
  view.value = next;
  pushSettingsView(next);
  void loadForScope();
}

function selectScope(scope: string): void {
  // Keep the tab when hopping scopes, never a stale form — its cascade
  // context shifts with the scope.
  navigate({ scope, form: null, tab: view.value.tab });
  if (scopeKeyword(scope) === null) rememberProject(scope);
}

function selectForm(name: string): void {
  navigate({ ...view.value, form: name });
}

function backToListing(): void {
  navigate({ ...view.value, form: null });
}

function selectTab(tab: SettingsView['tab']): void {
  navigate({ ...view.value, tab });
}

function onFormApplied(): void {
  formReloadKey.value += 1;
  void loadForms();
}

// ─── Browser history (back/forward re-parses the URL) ───

function onPopState(): void {
  view.value = parseSettingsView(window.location.search, view.value.scope);
  void loadForScope();
}

onMounted(async () => {
  window.addEventListener('popstate', onPopState);
  await Promise.all([
    tenantProjects.reload(),
    profile.load().catch(() => undefined),
  ]);
  // URL first; a remembered project is the fallback; the tenant row is
  // the built-in default one click away at the top of the sidebar.
  const urlView = parseSettingsView(window.location.search, '');
  const remembered = recallProject(selectableProjects.value.map((p) => p.name));
  const initialScope =
    urlView.scope
    || (remembered && selectableProjects.value.some((p) => p.name === remembered)
      ? remembered
      : TENANT_SCOPE);
  view.value = { ...urlView, scope: initialScope };
  replaceSettingsView(view.value);
  await loadForScope();
});

onUnmounted(() => {
  window.removeEventListener('popstate', onPopState);
});


// ─── Guided inventory: forms + doc rows grouped by category ───

interface InventoryEntry {
  key: string;
  category: string;
  title: string;
  description: string;
  /** Setting Form name — set for form entries, absent for doc rows. */
  formName?: string;
  /** Same-origin link opening the kind's normal editor. */
  href?: string;
}

const inventory = computed<InventoryEntry[]>(() => [
  ...forms.value.map((f) => ({
    key: `form:${f.name}`,
    category: f.category ?? '',
    title: f.title,
    description: f.description,
    formName: f.name,
  })),
  ...docRows.value.map((d) => ({
    key: `doc:${d.kindId}:${d.name}`,
    category: d.category,
    title: d.title,
    description: d.description ?? '',
    href: d.href,
  })),
]);

const groupedInventory = computed<[string, InventoryEntry[]][]>(() => {
  const groups = new Map<string, InventoryEntry[]>();
  for (const entry of inventory.value) {
    const cat = entry.category || '';
    if (!groups.has(cat)) groups.set(cat, []);
    groups.get(cat)!.push(entry);
  }
  for (const list of groups.values()) {
    list.sort((a, b) => a.title.localeCompare(b.title));
  }
  return [...groups.entries()].sort((a, b) => a[0].localeCompare(b[0]));
});

const hasInventory = computed(() => inventory.value.length > 0);
const inventoryBusy = computed(() => formsLoading.value || docsLoading.value);
</script>

<template>
  <EditorShell
    :title="t('settings.pageTitle')"
    :breadcrumbs="breadcrumbs"
    :show-sidebar="true"
  >
    <!-- ─── Sidebar: the three scope rows ─── -->
    <template #sidebar>
      <div class="flex flex-col">
        <nav class="flex flex-col gap-1 p-2">
          <button
            type="button"
            class="sidebar-item"
            :class="{ 'sidebar-item--active': scopeKeyword(view.scope) === 'user' }"
            :disabled="!login"
            @click="selectScope(USER_SCOPE)"
          >
            <span class="opacity-50 mr-1">☺</span>{{ t('settings.sidebar.user') }}
          </button>
          <button
            type="button"
            class="sidebar-item"
            :class="{ 'sidebar-item--active': scopeKeyword(view.scope) === 'tenant' }"
            @click="selectScope(TENANT_SCOPE)"
          >
            <span class="opacity-50 mr-1">⌂</span>{{ t('settings.sidebar.tenant') }}
          </button>
        </nav>

        <ProjectListSidebar
          v-model:selected-project="sidebarProject"
          :groups="tenantProjects.groups.value"
          :projects="selectableProjects"
          :loading="tenantProjects.loading.value"
          :error="tenantProjects.error.value"
          :heading="t('settings.sidebar.projects')"
          :ungrouped-label="t('settings.sidebar.ungrouped')"
          :empty-headline="t('settings.sidebar.emptyHeadline')"
          :empty-body="t('settings.sidebar.emptyBody')"
          search-enabled
          hide-kit-field
        />
      </div>
    </template>

    <!-- ─── Main: scope header + tabs ─── -->
    <div class="p-6 max-w-3xl flex flex-col gap-4">
      <div class="flex items-baseline justify-between gap-2">
        <h2 class="text-lg font-semibold">{{ scopeLabel }}</h2>
        <span class="text-xs opacity-60 font-mono">{{ scopeInfo.referenceId }}</span>
      </div>

      <div role="tablist" class="flex gap-1 border-b border-base-300">
        <button
          type="button"
          role="tab"
          class="px-3 py-1.5 text-sm font-semibold border-b-2 transition-colors"
          :class="view.tab === 'guided'
            ? 'border-primary text-primary'
            : 'border-transparent opacity-60 hover:opacity-100'"
          @click="selectTab('guided')"
        >
          {{ t('settings.tab.guided') }}
        </button>
        <button
          type="button"
          role="tab"
          class="px-3 py-1.5 text-sm font-semibold border-b-2 transition-colors"
          :class="view.tab === 'raw'
            ? 'border-primary text-primary'
            : 'border-transparent opacity-60 hover:opacity-100'"
          @click="selectTab('raw')"
        >
          {{ t('settings.tab.raw') }}
        </button>
      </div>

      <!-- ─── Tab: guided inventory ─── -->
      <template v-if="view.tab === 'guided'">
        <template v-if="activeForm">
          <div class="flex items-center gap-2">
            <VButton variant="ghost" size="sm" @click="backToListing">
              {{ t('settings.backToListing') }}
            </VButton>
          </div>
          <SettingFormView
            :name="activeForm.name"
            :project-id="formsProjectId"
            :reload-key="formReloadKey"
            @applied="onFormApplied"
          />
        </template>

        <template v-else>
          <VAlert v-if="formsError" variant="error">{{ formsError }}</VAlert>

          <VEmptyState
            v-else-if="!inventoryBusy && !hasInventory"
            :headline="t('settings.emptyHeadline')"
            :body="t('settings.emptyBody')"
          />

          <div
            v-for="[cat, group] in groupedInventory"
            :key="cat"
            class="flex flex-col gap-1"
          >
            <div
              v-if="cat"
              class="text-[10px] uppercase tracking-wide opacity-50 font-semibold px-1 mt-1"
            >
              {{ cat }}
            </div>
            <template v-for="entry in group" :key="entry.key">
              <button
                v-if="entry.formName"
                type="button"
                class="text-left px-3 py-2 text-sm rounded transition-colors bg-base-200 hover:bg-base-300"
                @click="selectForm(entry.formName)"
              >
                <div class="font-semibold truncate">{{ entry.title }}</div>
                <div class="text-xs opacity-70 mt-0.5 line-clamp-2">{{ entry.description }}</div>
              </button>
              <a
                v-else-if="entry.href"
                :href="entry.href"
                class="block px-3 py-2 text-sm rounded transition-colors bg-base-200 hover:bg-base-300"
              >
                <div class="font-semibold truncate">{{ entry.title }}</div>
                <div class="text-xs opacity-70 mt-0.5">
                  {{ t('settings.openInEditor') }}
                </div>
              </a>
            </template>
          </div>
        </template>
      </template>

      <!-- ─── Tab: advanced raw editor ─── -->
      <template v-else>
        <p class="text-xs opacity-60">{{ t('settings.raw.hint') }}</p>
        <RawSettingsPanel
          :reference-type="scopeInfo.kind"
          :reference-id="scopeInfo.referenceId"
        />
      </template>
    </div>
  </EditorShell>
</template>

<style scoped>
.sidebar-item {
  text-align: left;
  padding: 0.375rem 0.5rem;
  border-radius: 0.25rem;
  font-size: 0.875rem;
  transition: background-color 0.15s ease;
}

.sidebar-item:hover {
  background-color: color-mix(in srgb, currentColor 8%, transparent);
}

.sidebar-item--active {
  background-color: color-mix(in srgb, currentColor 12%, transparent);
  font-weight: 600;
}

.sidebar-item:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
</style>

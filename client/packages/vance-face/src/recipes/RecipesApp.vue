<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import yaml from 'js-yaml';
import {
  CodeEditor,
  EditorShell,
  MarkdownView,
  VAlert,
  VButton,
  VCard,
  VEmptyState,
  VInput,
  VModal,
  VSelect,
} from '@/components';
import { useTenantProjects } from '@/composables/useTenantProjects';
import { useAdminRecipes } from '@/composables/useAdminRecipes';
import { useHelp } from '@/composables/useHelp';
import {
  PromptMode,
  RecipeSource,
  type RecipeDto,
  type RecipeWriteRequest,
} from '@vance/generated';

const tenantProjects = useTenantProjects();
const recipesState = useAdminRecipes();
const help = useHelp();

/**
 * Optional fields that the snippet inserter can add. Order matches
 * the wizard panel; defaults match {@link RecipeWriteRequest} shape.
 */
interface SnippetSpec {
  key: string;
  label: string;
  defaultValue: unknown;
}
const SNIPPETS: SnippetSpec[] = [
  { key: 'params', label: 'params', defaultValue: { model: 'default:fast' } },
  { key: 'allowedToolsAdd', label: 'allowedToolsAdd', defaultValue: [] },
  { key: 'allowedToolsRemove', label: 'allowedToolsRemove', defaultValue: [] },
  { key: 'tags', label: 'tags', defaultValue: [] },
  { key: 'promptPrefix', label: 'promptPrefix', defaultValue: 'Your prompt prefix here.\n' },
  { key: 'promptPrefixSmall', label: 'promptPrefixSmall', defaultValue: 'Variant for small models.\n' },
  { key: 'intentCorrection', label: 'intentCorrection', defaultValue: 'Correction message here.\n' },
  { key: 'dataRelayCorrection', label: 'dataRelayCorrection', defaultValue: 'Correction message here.\n' },
  { key: 'locked', label: 'locked', defaultValue: false },
];

/** Currently-selected scope. {@code null} = tenant, otherwise project name. */
const currentProject = ref<string | null>(null);

/** Name of the recipe shown in the editor pane, or null when none. */
const selectedName = ref<string | null>(null);

/** YAML buffer being edited. */
const yamlBuffer = ref('');

const banner = ref<string | null>(null);
const yamlError = ref<string | null>(null);

// ─── New-recipe modal ───
const showNewModal = ref(false);
const newName = ref('');
const newEngine = ref('ford');
const newDescription = ref('');
const newError = ref<string | null>(null);

const NAME_PATTERN = /^[a-z0-9][a-z0-9_-]*$/;

const projectOptions = computed(() => [
  { value: '', label: '(Tenant)' },
  ...tenantProjects.projects.value.map(p => ({
    value: p.name,
    label: p.title || p.name,
    group: p.projectGroupId ?? 'Ungrouped',
  })),
]);

/** Selected scope's owning side, used to decide Save target + Delete eligibility. */
const currentSourceForScope = computed<RecipeSource>(() =>
  currentProject.value ? RecipeSource.PROJECT : RecipeSource.TENANT);

const selectedRecipe = computed<RecipeDto | null>(() => {
  if (!selectedName.value) return null;
  return recipesState.recipes.value.find(r => r.name === selectedName.value) ?? null;
});

const isOwnedAtCurrentScope = computed(() =>
  selectedRecipe.value?.source === currentSourceForScope.value);

function sourceLabelFor(s: RecipeSource | undefined): string {
  if (s === RecipeSource.BUNDLED) return 'bundled';
  if (s === RecipeSource.TENANT) return 'tenant';
  if (s === RecipeSource.PROJECT) return 'project';
  return '';
}

const sourceLabel = computed(() => sourceLabelFor(selectedRecipe.value?.source));

const breadcrumbs = computed<string[]>(() => {
  const scope = currentProject.value ? `Project: ${currentProject.value}` : 'Tenant';
  return selectedName.value ? [scope, selectedName.value] : [scope];
});

onMounted(async () => {
  await Promise.all([
    tenantProjects.reload(),
    recipesState.loadEffective(null),
    help.load('recipe-field-docs.md'),
  ]);
});

watch(currentProject, async (nv) => {
  selectedName.value = null;
  yamlBuffer.value = '';
  await recipesState.loadEffective(nv);
});

watch(selectedName, () => {
  banner.value = null;
  yamlError.value = null;
  if (!selectedRecipe.value) {
    yamlBuffer.value = '';
    return;
  }
  yamlBuffer.value = serialize(selectedRecipe.value);
});

function selectRecipe(name: string): void {
  selectedName.value = name;
}

function openNewRecipe(): void {
  newName.value = '';
  newEngine.value = 'ford';
  newDescription.value = '';
  newError.value = null;
  showNewModal.value = true;
}

async function submitNewRecipe(): Promise<void> {
  newError.value = null;
  const name = newName.value.trim();
  const engine = newEngine.value.trim();
  const description = newDescription.value.trim();
  if (!name) {
    newError.value = 'Name is required.';
    return;
  }
  if (!NAME_PATTERN.test(name)) {
    newError.value = 'Name must be lower-case alphanumerics with optional "-" or "_".';
    return;
  }
  if (recipesState.recipes.value.some(r => r.name === name)) {
    newError.value = `A recipe named "${name}" already exists in the cascade. Pick a different name (or open the existing one to override it).`;
    return;
  }
  if (!engine) {
    newError.value = 'Engine is required.';
    return;
  }
  if (!description) {
    newError.value = 'Description is required.';
    return;
  }
  const stub: RecipeWriteRequest = {
    description,
    engine,
    params: {},
    promptMode: PromptMode.APPEND,
    locked: false,
    tags: [],
    allowedToolsAdd: [],
    allowedToolsRemove: [],
  };
  try {
    await recipesState.upsert(currentProject.value, name, stub);
    showNewModal.value = false;
    selectedName.value = name;
    banner.value = `Created recipe "${name}" at ${currentProject.value ? 'project' : 'tenant'} scope.`;
  } catch (e) {
    newError.value = e instanceof Error ? e.message : 'Failed to create recipe.';
  }
}

function serialize(r: RecipeDto): string {
  // Build the YAML body. {@code name} stays out — it's the path key,
  // edited via "rename" elsewhere if ever. {@code source} is metadata
  // for the UI, not for the writer.
  const body: Record<string, unknown> = {
    description: r.description,
    engine: r.engine,
    promptMode: r.promptMode,
    locked: r.locked,
    tags: r.tags ?? [],
    params: r.params ?? {},
    allowedToolsAdd: r.allowedToolsAdd ?? [],
    allowedToolsRemove: r.allowedToolsRemove ?? [],
  };
  if (r.intentCorrection) body.intentCorrection = r.intentCorrection;
  if (r.dataRelayCorrection) body.dataRelayCorrection = r.dataRelayCorrection;
  if (r.promptPrefix) body.promptPrefix = r.promptPrefix;
  if (r.promptPrefixSmall) body.promptPrefixSmall = r.promptPrefixSmall;
  return yaml.dump(body, {
    lineWidth: 100,
    noRefs: true,
    sortKeys: false,
  });
}

function parse(): RecipeWriteRequest | null {
  yamlError.value = null;
  let parsed: unknown;
  try {
    parsed = yaml.load(yamlBuffer.value);
  } catch (e) {
    yamlError.value = e instanceof Error ? e.message : 'YAML parse error.';
    return null;
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    yamlError.value = 'YAML root must be a map.';
    return null;
  }
  const obj = parsed as Record<string, unknown>;
  const description = stringOrEmpty(obj.description);
  const engine = stringOrEmpty(obj.engine);
  if (!description) {
    yamlError.value = '`description` is required.';
    return null;
  }
  if (!engine) {
    yamlError.value = '`engine` is required.';
    return null;
  }
  const promptMode = parsePromptMode(obj.promptMode);
  if (!promptMode) {
    yamlError.value = '`promptMode` must be APPEND or OVERWRITE.';
    return null;
  }
  return {
    description,
    engine,
    params: (obj.params as Record<string, unknown> | undefined) ?? {},
    promptPrefix: stringOrUndef(obj.promptPrefix),
    promptPrefixSmall: stringOrUndef(obj.promptPrefixSmall),
    promptMode,
    intentCorrection: stringOrUndef(obj.intentCorrection),
    dataRelayCorrection: stringOrUndef(obj.dataRelayCorrection),
    allowedToolsAdd: stringList(obj.allowedToolsAdd),
    allowedToolsRemove: stringList(obj.allowedToolsRemove),
    locked: obj.locked === true,
    tags: stringList(obj.tags),
  };
}

function stringOrEmpty(v: unknown): string {
  return typeof v === 'string' ? v : '';
}
function stringOrUndef(v: unknown): string | undefined {
  return typeof v === 'string' && v.length > 0 ? v : undefined;
}
function stringList(v: unknown): string[] {
  if (!Array.isArray(v)) return [];
  return v.filter((x): x is string => typeof x === 'string');
}
function parsePromptMode(v: unknown): PromptMode | null {
  if (v === PromptMode.APPEND || v === PromptMode.OVERWRITE) return v;
  if (typeof v === 'string') {
    const u = v.trim().toUpperCase();
    if (u === 'APPEND') return PromptMode.APPEND;
    if (u === 'OVERWRITE') return PromptMode.OVERWRITE;
  }
  return null;
}

async function saveOverride(): Promise<void> {
  if (!selectedName.value) return;
  const req = parse();
  if (!req) return;
  banner.value = null;
  try {
    await recipesState.upsert(currentProject.value, selectedName.value, req);
    banner.value = isOwnedAtCurrentScope.value
      ? `Saved.`
      : `Override created at ${currentProject.value ? 'project' : 'tenant'} scope.`;
  } catch {
    /* error already in recipesState.error */
  }
}

/**
 * Inserts a snippet for {@code spec.key} into the current YAML buffer
 * if not already present. Idempotent: if the key already exists, the
 * call is a no-op and the button hides.
 */
function insertSnippet(spec: SnippetSpec): void {
  yamlError.value = null;
  let parsed: Record<string, unknown>;
  try {
    const raw = yaml.load(yamlBuffer.value);
    parsed = (raw && typeof raw === 'object' && !Array.isArray(raw))
      ? (raw as Record<string, unknown>)
      : {};
  } catch (e) {
    yamlError.value = e instanceof Error ? e.message : 'YAML parse error.';
    return;
  }
  if (spec.key in parsed) return;
  parsed[spec.key] = structuredClone(spec.defaultValue);
  yamlBuffer.value = yaml.dump(parsed, { lineWidth: 100, noRefs: true, sortKeys: false });
}

/** Returns the keys currently present in the YAML buffer (best-effort). */
function presentKeys(): Set<string> {
  try {
    const raw = yaml.load(yamlBuffer.value);
    if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
      return new Set(Object.keys(raw as Record<string, unknown>));
    }
  } catch {
    /* parse error — show all snippets as available */
  }
  return new Set();
}

const availableSnippets = computed<SnippetSpec[]>(() => {
  const present = presentKeys();
  return SNIPPETS.filter(s => !present.has(s.key));
});

async function deleteOverride(): Promise<void> {
  if (!selectedName.value || !isOwnedAtCurrentScope.value) return;
  if (!confirm(`Remove the ${currentProject.value ? 'project' : 'tenant'}-scope override of "${selectedName.value}"?`)) return;
  try {
    await recipesState.remove(currentProject.value, selectedName.value);
    banner.value = `Override removed — recipe falls back to inherited copy.`;
  } catch {
    /* error already in recipesState.error */
  }
}

const combinedError = computed<string | null>(() =>
  recipesState.error.value || tenantProjects.error.value);
</script>

<template>
  <EditorShell title="Recipes" :breadcrumbs="breadcrumbs" wide-right-panel>
    <template #topbar-extra>
      <VSelect
        :model-value="currentProject ?? ''"
        :options="projectOptions"
        @update:model-value="(v) => currentProject = (v ? String(v) : null)"
      />
    </template>

    <!-- ─── Sidebar: list of effective recipes ─── -->
    <template #sidebar>
      <nav class="flex flex-col gap-1 p-2">
        <div class="flex items-center justify-between px-2 mb-1">
          <span class="text-xs uppercase opacity-50">
            {{ currentProject ? `Project: ${currentProject}` : 'Tenant scope' }}
          </span>
          <VButton variant="ghost" size="sm" @click="openNewRecipe">+ New</VButton>
        </div>

        <div v-if="recipesState.loading.value" class="px-2 text-xs opacity-60">
          Loading…
        </div>
        <VEmptyState
          v-else-if="recipesState.recipes.value.length === 0"
          headline="No recipes"
          body="Bundled catalog is empty — check brain logs."
        />

        <button
          v-for="r in recipesState.recipes.value"
          :key="r.name"
          class="recipe-item"
          :class="{ 'recipe-item--active': selectedName === r.name }"
          type="button"
          @click="selectRecipe(r.name)"
        >
          <div class="flex items-center justify-between gap-2">
            <span class="font-mono text-sm truncate">{{ r.name }}</span>
            <span
              class="text-xs px-1.5 py-0.5 rounded"
              :class="[
                r.source === RecipeSource.BUNDLED ? 'badge-bundled' : '',
                r.source === RecipeSource.TENANT ? 'badge-tenant' : '',
                r.source === RecipeSource.PROJECT ? 'badge-project' : '',
              ]"
            >{{ sourceLabelFor(r.source) }}</span>
          </div>
          <div class="text-xs opacity-60 truncate">{{ r.engine }} · {{ r.description }}</div>
        </button>
      </nav>
    </template>

    <!-- ─── Main: YAML editor ─── -->
    <div class="p-6 flex flex-col gap-3 max-w-5xl">
      <VAlert v-if="combinedError" variant="error">
        <span>{{ combinedError }}</span>
      </VAlert>
      <VAlert v-if="banner" variant="success">
        <span>{{ banner }}</span>
      </VAlert>
      <VAlert v-if="yamlError" variant="error">
        <span>{{ yamlError }}</span>
      </VAlert>

      <VEmptyState
        v-if="!selectedRecipe"
        headline="Select a recipe"
        body="Pick an entry on the left to view or edit. The YAML carries every field the brain understands."
      />

      <template v-else>
        <VCard>
          <div class="flex items-center justify-between gap-3">
            <div>
              <div class="font-mono text-lg">{{ selectedRecipe.name }}</div>
              <div class="text-sm opacity-70">
                Effective at <strong>{{ currentProject ? `project ${currentProject}` : 'tenant' }}</strong>
                · source <strong>{{ sourceLabel }}</strong>
              </div>
            </div>
            <div class="flex gap-2">
              <VButton
                v-if="isOwnedAtCurrentScope"
                variant="danger"
                :loading="recipesState.busy.value"
                @click="deleteOverride"
              >Delete override</VButton>
              <VButton
                variant="primary"
                :loading="recipesState.busy.value"
                @click="saveOverride"
              >{{ isOwnedAtCurrentScope ? 'Save' : 'Override here' }}</VButton>
            </div>
          </div>
          <VAlert v-if="!isOwnedAtCurrentScope" variant="info" class="mt-3">
            <span>
              This recipe is inherited from <strong>{{ sourceLabel }}</strong>.
              Click "Override here" to copy-on-write into the
              {{ currentProject ? 'project' : 'tenant' }} scope.
            </span>
          </VAlert>
        </VCard>

        <CodeEditor
          v-model="yamlBuffer"
          mime-type="application/yaml"
          :rows="32"
        />
      </template>
    </div>

    <!-- ─── Right panel: snippet inserter + field reference ─── -->
    <template #right-panel>
      <div class="p-4 flex flex-col gap-4">
        <section v-if="selectedRecipe">
          <h3 class="text-xs uppercase opacity-60 mb-2">Add field</h3>
          <div v-if="availableSnippets.length === 0" class="text-xs opacity-60">
            All known optional fields are already present in this recipe.
          </div>
          <div v-else class="flex flex-wrap gap-2">
            <VButton
              v-for="s in availableSnippets"
              :key="s.key"
              variant="ghost"
              size="sm"
              @click="insertSnippet(s)"
            >+ {{ s.label }}</VButton>
          </div>
        </section>

        <section>
          <h3 class="text-xs uppercase opacity-60 mb-2">Field reference</h3>
          <div v-if="help.loading.value" class="text-xs opacity-60">
            Loading…
          </div>
          <div v-else-if="help.error.value" class="text-xs opacity-60">
            Help unavailable: {{ help.error.value }}
          </div>
          <div v-else-if="!help.content.value" class="text-xs opacity-60">
            No help content for this resource.
          </div>
          <MarkdownView v-else :source="help.content.value" />
        </section>
      </div>
    </template>

    <!-- ─── New-recipe modal ─── -->
    <VModal v-model="showNewModal" title="New recipe">
      <div class="flex flex-col gap-3">
        <VAlert v-if="newError" variant="error">
          <span>{{ newError }}</span>
        </VAlert>
        <VInput
          v-model="newName"
          label="Name"
          required
          help="Lower-case alphanumerics, '-' or '_' allowed. Must not collide with an existing recipe."
        />
        <VInput
          v-model="newEngine"
          label="Engine"
          required
          help="Engine name from the registry — e.g. ford, arthur, marvin, vogon, zaphod."
        />
        <VInput v-model="newDescription" label="Description" required />
        <p class="text-xs opacity-70">
          A stub recipe will be created at the
          <strong>{{ currentProject ? `project "${currentProject}"` : 'tenant' }}</strong>
          scope. Refine the YAML afterwards.
        </p>
        <div class="flex justify-end gap-2">
          <VButton variant="ghost" @click="showNewModal = false">Cancel</VButton>
          <VButton
            variant="primary"
            :loading="recipesState.busy.value"
            @click="submitNewRecipe"
          >Create</VButton>
        </div>
      </div>
    </VModal>
  </EditorShell>
</template>

<style scoped>
.recipe-item {
  display: block;
  text-align: left;
  padding: 0.5rem 0.6rem;
  border-radius: 0.375rem;
  background: transparent;
  cursor: pointer;
  width: 100%;
  border: 1px solid transparent;
}
.recipe-item:hover {
  background: hsl(var(--bc) / 0.06);
}
.recipe-item--active {
  background: hsl(var(--p) / 0.12);
  border-color: hsl(var(--p) / 0.3);
}
.badge-bundled {
  background: hsl(var(--bc) / 0.08);
  color: hsl(var(--bc) / 0.7);
}
.badge-tenant {
  background: hsl(var(--in) / 0.18);
  color: hsl(var(--inc));
}
.badge-project {
  background: hsl(var(--su) / 0.18);
  color: hsl(var(--suc));
}
</style>

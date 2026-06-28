<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { brainFetch, documentContentUrl } from '@vance/shared';

/**
 * Asset picker — three tabs for the three image sources Vance knows
 * about:
 *
 *   1. **App**   — images uploaded into this workspace's `assets/`
 *                  folder; what the upload button still feeds.
 *   2. **Project** — every image anywhere in the current project,
 *                  server-side searchable so the user finds a picture
 *                  buried under any folder without it needing to be
 *                  duplicated into the workspace.
 *   3. **Shared** — flat list under `workspace/images/` in the
 *                  tenant-wide `_tenant` project. Vance ships some
 *                  defaults here and org-admins can add more.
 *                  Gracefully hidden if the user has no read access.
 *
 * The picker emits a {@code vance:} URI rather than an absolute HTTP
 * URL so the on-disk Markdown stays portable. The block-editor's
 * image NodeView resolves it back to a real `<img src>` on render.
 */
const props = defineProps<{
  projectId: string;
  /** Workspace folder root — assets live under `<folder>/assets/`. */
  workspaceFolder: string;
  uploadImage: (file: File) => Promise<string | null>;
}>();

const emit = defineEmits<{
  (e: 'pick', src: string, alt: string): void;
  (e: 'close'): void;
}>();

interface AssetItem {
  id: string;
  path: string;
  name: string;
  mimeType: string | null;
}

type TabId = 'app' | 'project' | 'shared';
const TENANT_PROJECT = '_tenant';
const SHARED_PREFIX = 'workspace/images/';

const tab = ref<TabId>('app');

// --- Tab 1: App ---------------------------------------------------
const assetsFolder = computed(() => `${props.workspaceFolder}/assets/`);
const appAssets = ref<AssetItem[]>([]);
const appLoading = ref(false);
const appError = ref<string | null>(null);
const uploading = ref(false);
const fileInputRef = ref<HTMLInputElement | null>(null);

async function loadAppAssets() {
  appLoading.value = true;
  appError.value = null;
  try {
    const resp = await brainFetch<{ items: AssetItem[] }>(
      'GET',
      `documents?projectId=${encodeURIComponent(props.projectId)}` +
        `&pathPrefix=${encodeURIComponent(assetsFolder.value)}` +
        `&size=200`,
    );
    appAssets.value = (resp.items ?? []).filter((i) =>
      (i.mimeType ?? '').startsWith('image/'),
    );
  } catch (e) {
    appError.value = e instanceof Error ? e.message : 'Could not load assets.';
    appAssets.value = [];
  } finally {
    appLoading.value = false;
  }
}

// --- Tab 2: Project (server-side search) --------------------------
const projectQuery = ref('');
const projectAssets = ref<AssetItem[]>([]);
const projectLoading = ref(false);
const projectError = ref<string | null>(null);
const projectTotal = ref(0);
let projectSearchTimer: ReturnType<typeof setTimeout> | null = null;

async function loadProjectAssets(query: string) {
  projectLoading.value = true;
  projectError.value = null;
  try {
    const params = new URLSearchParams();
    params.set('projectId', props.projectId);
    if (query) params.set('query', query);
    params.set('size', '60');
    const resp = await brainFetch<{ items: AssetItem[]; total: number }>(
      'GET',
      `addon/workspace/images?${params}`,
    );
    projectAssets.value = resp.items ?? [];
    projectTotal.value = resp.total ?? projectAssets.value.length;
  } catch (e) {
    projectError.value = e instanceof Error ? e.message : 'Search failed.';
    projectAssets.value = [];
    projectTotal.value = 0;
  } finally {
    projectLoading.value = false;
  }
}

function scheduleProjectSearch() {
  if (projectSearchTimer != null) clearTimeout(projectSearchTimer);
  projectSearchTimer = setTimeout(() => {
    projectSearchTimer = null;
    void loadProjectAssets(projectQuery.value.trim());
  }, 250);
}

// --- Tab 3: Shared (_tenant) -------------------------------------
const sharedAssets = ref<AssetItem[]>([]);
const sharedLoading = ref(false);
const sharedError = ref<string | null>(null);
const sharedAvailable = ref<boolean | null>(null); // null = not probed yet

async function loadSharedAssets() {
  sharedLoading.value = true;
  sharedError.value = null;
  try {
    const params = new URLSearchParams();
    params.set('projectId', TENANT_PROJECT);
    params.set('pathPrefix', SHARED_PREFIX);
    params.set('size', '200');
    const resp = await brainFetch<{ items: AssetItem[] }>(
      'GET',
      `addon/workspace/images?${params}`,
      { authenticated: true },
    );
    sharedAssets.value = (resp.items ?? []).filter((i) =>
      (i.mimeType ?? '').startsWith('image/'),
    );
    sharedAvailable.value = true;
  } catch (e) {
    // 403 / 404 → user has no read access on _tenant; hide the tab
    // gracefully rather than treating it as a hard error.
    const msg = e instanceof Error ? e.message : '';
    if (/\b(403|404|forbidden|not found|no permission)/i.test(msg)) {
      sharedAvailable.value = false;
    } else {
      sharedError.value = msg || 'Could not load shared images.';
      sharedAvailable.value = true;
    }
    sharedAssets.value = [];
  } finally {
    sharedLoading.value = false;
  }
}

// --- Picking + emit ----------------------------------------------
function vanceUriForApp(asset: AssetItem): string {
  return `vance:/${encodeURI(asset.path)}?kind=image`;
}
function vanceUriForProject(asset: AssetItem): string {
  return `vance:/${encodeURI(asset.path)}?kind=image`;
}
function vanceUriForShared(asset: AssetItem): string {
  return `vance://${encodeURIComponent(TENANT_PROJECT)}/${encodeURI(asset.path)}?kind=image`;
}

function pick(asset: AssetItem, source: TabId) {
  const uri = source === 'shared'
    ? vanceUriForShared(asset)
    : source === 'project'
      ? vanceUriForProject(asset)
      : vanceUriForApp(asset);
  emit('pick', uri, asset.name);
}

// HTTP src for the thumbnail preview inside the modal — the embedded
// `<img>` inside this modal lives outside the Tiptap NodeView so it
// can't share the editor's resolver. Resolving here directly via
// documentContentUrl(id) is the simplest path and the modal closes
// once the user picks, so caching is moot.
function thumbUrl(asset: AssetItem): string {
  return documentContentUrl(asset.id);
}

// --- Lifecycle ----------------------------------------------------
function close() { emit('close'); }
function openFileBrowser() { fileInputRef.value?.click(); }
async function onFileSelected(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  uploading.value = true;
  try {
    const url = await props.uploadImage(file);
    if (url) emit('pick', url, file.name);
  } finally {
    uploading.value = false;
  }
}
function onBackdrop(e: MouseEvent) {
  if (e.target === e.currentTarget) close();
}

watch(tab, (next) => {
  if (next === 'project' && projectAssets.value.length === 0 && !projectLoading.value) {
    void loadProjectAssets('');
  }
  if (next === 'shared' && sharedAvailable.value === null) {
    void loadSharedAssets();
  }
});

onMounted(() => {
  void loadAppAssets();
});
</script>

<template>
  <div class="asset-picker" @click="onBackdrop">
    <div class="asset-picker__panel">
      <header class="asset-picker__header">
        <span>Insert image</span>
        <button class="asset-picker__close" type="button" @click="close">×</button>
      </header>

      <nav class="asset-picker__tabs">
        <button
          type="button"
          class="asset-picker__tab"
          :class="{ 'asset-picker__tab--active': tab === 'app' }"
          @click="tab = 'app'"
        >App</button>
        <button
          type="button"
          class="asset-picker__tab"
          :class="{ 'asset-picker__tab--active': tab === 'project' }"
          @click="tab = 'project'"
        >Project</button>
        <button
          v-if="sharedAvailable !== false"
          type="button"
          class="asset-picker__tab"
          :class="{ 'asset-picker__tab--active': tab === 'shared' }"
          @click="tab = 'shared'"
        >Shared</button>
      </nav>

      <!-- ── Tab: App ────────────────────────────────────────────── -->
      <template v-if="tab === 'app'">
        <div class="asset-picker__actions">
          <button
            type="button"
            class="asset-picker__upload-btn"
            :disabled="uploading"
            @click="openFileBrowser"
          >{{ uploading ? 'Uploading…' : '⤴ Upload new' }}</button>
          <input
            ref="fileInputRef"
            type="file"
            accept="image/*"
            class="asset-picker__file-input"
            @change="onFileSelected"
          />
        </div>
        <div v-if="appError" class="asset-picker__error">{{ appError }}</div>
        <div v-if="appLoading" class="asset-picker__loading">Lade Assets…</div>
        <div v-else-if="appAssets.length === 0" class="asset-picker__empty">
          Noch keine Bilder unter <code>{{ assetsFolder }}</code>.
        </div>
        <div v-else class="asset-picker__grid">
          <button
            v-for="a in appAssets"
            :key="a.id"
            type="button"
            class="asset-picker__item"
            :title="a.path"
            @click="pick(a, 'app')"
          >
            <img :src="thumbUrl(a)" :alt="a.name" class="asset-picker__thumb" />
            <div class="asset-picker__item-name">{{ a.name }}</div>
          </button>
        </div>
      </template>

      <!-- ── Tab: Project (server-side search) ───────────────────── -->
      <template v-else-if="tab === 'project'">
        <div class="asset-picker__actions">
          <input
            v-model="projectQuery"
            type="search"
            class="asset-picker__search-input"
            placeholder="Search images by name or path…"
            @input="scheduleProjectSearch"
          />
        </div>
        <div v-if="projectError" class="asset-picker__error">{{ projectError }}</div>
        <div v-if="projectLoading" class="asset-picker__loading">Suche…</div>
        <div v-else-if="projectAssets.length === 0" class="asset-picker__empty">
          Keine Bilder im Projekt gefunden.
        </div>
        <div v-else class="asset-picker__grid">
          <button
            v-for="a in projectAssets"
            :key="a.id"
            type="button"
            class="asset-picker__item"
            :title="a.path"
            @click="pick(a, 'project')"
          >
            <img :src="thumbUrl(a)" :alt="a.name" class="asset-picker__thumb" />
            <div class="asset-picker__item-name">{{ a.name }}</div>
            <div class="asset-picker__item-path">{{ a.path }}</div>
          </button>
        </div>
        <div
          v-if="projectAssets.length > 0 && projectTotal > projectAssets.length"
          class="asset-picker__truncated"
        >
          Showing {{ projectAssets.length }} of {{ projectTotal }} — refine the search to narrow.
        </div>
      </template>

      <!-- ── Tab: Shared (_tenant) ───────────────────────────────── -->
      <template v-else-if="tab === 'shared'">
        <div v-if="sharedError" class="asset-picker__error">{{ sharedError }}</div>
        <div v-if="sharedLoading" class="asset-picker__loading">Lade Shared Images…</div>
        <div v-else-if="sharedAssets.length === 0" class="asset-picker__empty">
          Noch keine geteilten Bilder unter <code>{{ SHARED_PREFIX }}</code> im
          <code>{{ TENANT_PROJECT }}</code>-Projekt.
        </div>
        <div v-else class="asset-picker__grid">
          <button
            v-for="a in sharedAssets"
            :key="a.id"
            type="button"
            class="asset-picker__item"
            :title="a.path"
            @click="pick(a, 'shared')"
          >
            <img :src="thumbUrl(a)" :alt="a.name" class="asset-picker__thumb" />
            <div class="asset-picker__item-name">{{ a.name }}</div>
          </button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.asset-picker {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 2rem;
}
.asset-picker__panel {
  background: oklch(var(--b1));
  border-radius: 0.5rem;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.15);
  width: 100%;
  max-width: 56rem;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.asset-picker__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.75rem 1rem;
  font-weight: 600;
  border-bottom: 1px solid oklch(var(--bc) / 0.18);
}
.asset-picker__close {
  background: none;
  border: none;
  font-size: 1.4rem;
  line-height: 1;
  cursor: pointer;
  color: oklch(var(--bc) / 0.65);
  padding: 0 0.25rem;
}
.asset-picker__tabs {
  display: flex;
  gap: 0.25rem;
  padding: 0.25rem 0.5rem;
  border-bottom: 1px solid oklch(var(--bc) / 0.18);
  background: oklch(var(--bc) / 0.06);
}
.asset-picker__tab {
  background: none;
  border: 0;
  padding: 0.4rem 0.8rem;
  border-radius: 0.25rem;
  cursor: pointer;
  font-size: 0.85rem;
  color: oklch(var(--bc) / 0.65);
}
.asset-picker__tab:hover { color: oklch(var(--bc)); }
.asset-picker__tab--active {
  background: oklch(var(--b1));
  color: oklch(var(--bc));
  font-weight: 600;
}
.asset-picker__actions {
  display: flex;
  gap: 0.5rem;
  padding: 0.5rem 1rem;
  border-bottom: 1px solid oklch(var(--bc) / 0.18);
}
.asset-picker__upload-btn {
  padding: 0.3rem 0.8rem;
  border: 1px solid oklch(var(--bc) / 0.18);
  border-radius: 0.25rem;
  background: oklch(var(--bc) / 0.06);
  cursor: pointer;
}
.asset-picker__upload-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.asset-picker__search-input {
  flex: 1;
  padding: 0.3rem 0.5rem;
  font-size: 0.9rem;
  border: 1px solid oklch(var(--bc) / 0.18);
  border-radius: 0.25rem;
  background: oklch(var(--b1));
}
.asset-picker__file-input { display: none; }
.asset-picker__error {
  background: oklch(var(--er) / 0.12);
  color: oklch(var(--er));
  font-size: 0.85rem;
  padding: 0.5rem 1rem;
}
.asset-picker__loading,
.asset-picker__empty {
  padding: 2rem;
  color: oklch(var(--bc) / 0.65);
  text-align: center;
  font-size: 0.9rem;
}
.asset-picker__empty code {
  font-family: monospace;
  font-size: 0.85em;
}
.asset-picker__truncated {
  padding: 0.5rem 1rem;
  font-size: 0.75rem;
  color: oklch(var(--bc) / 0.65);
  text-align: center;
  border-top: 1px solid oklch(var(--bc) / 0.18);
}
.asset-picker__grid {
  flex: 1;
  overflow-y: auto;
  padding: 1rem;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(8rem, 1fr));
  gap: 0.75rem;
}
.asset-picker__item {
  border: 1px solid oklch(var(--bc) / 0.18);
  border-radius: 0.375rem;
  background: oklch(var(--bc) / 0.06);
  cursor: pointer;
  padding: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  text-align: left;
  transition: box-shadow 0.15s ease;
}
.asset-picker__item:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}
.asset-picker__thumb {
  width: 100%;
  height: 7rem;
  object-fit: cover;
  background: oklch(var(--bc) / 0.06);
}
.asset-picker__item-name {
  padding: 0.4rem 0.5rem 0;
  font-size: 0.75rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: oklch(var(--bc) / 0.65);
  font-family: monospace;
}
.asset-picker__item-path {
  padding: 0 0.5rem 0.4rem;
  font-size: 0.65rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: oklch(var(--bc) / 0.65);
  font-family: monospace;
}
</style>

<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  useAppEntry,
  vanceRef,
  VAlert,
  VButton,
  VEmptyState,
  VInput,
  VModal,
  VTextarea,
} from '@vance/components';
import { VueDraggable } from 'vue-draggable-plus';
import {
  createDesign,
  createPreviewSession,
  deleteDesign,
  designContentUrl,
  getDesigner,
  reorderDesigns,
  updateDesignMeta,
} from './api';
import type { DesignInfo } from './generated/designer/DesignInfo';
import type { DesignerPreviewSession } from './generated/designer/DesignerPreviewSession';
import type { DesignerView } from './generated/designer/DesignerView';
import { useT } from './i18n';

/**
 * The designer app surface: a catalogue of the design folders under the
 * app folder on the left, the selected design rendered live on the right.
 *
 * <p><b>The sandbox is the security boundary.</b> The preview iframe runs
 * with {@code sandbox="allow-scripts"} and nothing else — an opaque
 * origin: the design's code can run and style itself, but it has no
 * cookies, no Vance API access, and no path back into the app around
 * it. Its URLs carry a short-lived preview token as a path segment
 * (see {@link designContentUrl}); the token is read-only, app-scoped,
 * and re-checked per fetch on the server.
 *
 * <p><b>Mutations answer with the catalogue.</b> Create, delete, reorder
 * and meta-edit all return a freshly scanned {@link DesignerView} — the
 * component replaces its state with it, so there is exactly one shape
 * to keep consistent, and a failed request reverts a drag by resyncing
 * from the server.
 */
const props = defineProps<{
  projectId: string;
  folder: string;
  documentId: string;
  title?: string | null;
}>();

const t = useT();

const view = ref<DesignerView | null>(null);
const loading = ref(true);
const error = ref<string | null>(null);
const mintError = ref<string | null>(null);
const selected = ref<string | null>(null);
const session = ref<DesignerPreviewSession | null>(null);
// Bumping this re-mounts the iframe — the reload button's mechanism.
const iframeKey = ref(0);
// The drag-and-drop's working copy: mutated in place by VueDraggable,
// resynced from `view` whenever the catalogue comes back from the server.
const designs = ref<DesignInfo[]>([]);
const busy = ref(false);

// ── Modals ─────────────────────────────────────────────────────

const createModal = ref(false);
const createName = ref('');
const createTitle = ref('');
const createDescription = ref('');

const editModal = ref(false);
const editName = ref('');
const editTitle = ref('');
const editDescription = ref('');

const deleteModal = ref(false);
const deleteName = ref('');

const selectedDesign = computed<DesignInfo | null>(() => {
  if (!selected.value) return null;
  return designs.value.find((d) => d.name === selected.value) ?? null;
});

/** Fresh-enough session? Re-mint inside the last 60 seconds of life. */
function sessionUsable(): boolean {
  const s = session.value;
  if (!s) return false;
  return new Date(s.expiresAt).getTime() - Date.now() > 60_000;
}

async function ensureSession(): Promise<string | null> {
  if (!sessionUsable()) {
    try {
      session.value = await createPreviewSession(props.projectId, props.folder);
      mintError.value = null;
    } catch (e) {
      mintError.value = t('designer.error.mint', {
        message: (e as Error).message,
      });
      return null;
    }
  }
  return session.value?.token ?? null;
}

const previewUrl = computed<string | null>(() => {
  if (!selectedDesign.value || !sessionUsable()) return null;
  return designContentUrl(props.documentId, session.value!.token, selectedDesign.value.name);
});

// ── Preview settings: color scheme, responsive preset, orientation ──

type ThemeMode = 'auto' | 'light' | 'dark';
type DevicePreset = 'desktop' | 'tablet' | 'phone';

const themeMode = ref<ThemeMode>('auto');
const device = ref<DevicePreset>('desktop');
const landscape = ref(false);

/** CSS pixel widths per preset, portrait/landscape (rotate swaps them). */
const DEVICE_WIDTHS: Record<DevicePreset, { portrait: number; landscape: number }> = {
  desktop: { portrait: 0, landscape: 0 },
  tablet: { portrait: 820, landscape: 1180 },
  phone: { portrait: 390, landscape: 844 },
};

/**
 * The iframe's own style. `color-scheme` on the iframe element propagates
 * into the nested browsing context — that is the whole mechanism behind
 * the day/night toggle: a design's `@media (prefers-color-scheme: dark)`
 * reacts to it, no design-side wiring needed. Width comes from the
 * responsive preset; desktop fills the area.
 */
const previewStyle = computed(() => {
  const style: Record<string, string> = {};
  if (themeMode.value !== 'auto') {
    style['color-scheme'] = themeMode.value;
  }
  const widths = DEVICE_WIDTHS[device.value];
  if (widths.portrait > 0) {
    style['width'] = `${landscape.value ? widths.landscape : widths.portrait}px`;
    style['max-width'] = '100%';
  }
  return style;
});

/** Narrow presets get a letterbox around the device frame; desktop fills. */
const previewLetterbox = computed(
  () => (device.value === 'desktop' ? 'bg-white' : 'bg-base-200'),
);

function setTheme(mode: ThemeMode): void {
  themeMode.value = mode;
}

function setDevice(preset: DevicePreset): void {
  device.value = preset;
  if (preset === 'desktop') {
    landscape.value = false;
  }
}

function toggleOrientation(): void {
  landscape.value = !landscape.value;
}

/**
 * Adopt a server catalogue: sort order, selection, drag copy. The
 * fallback pick (first design) is a normalisation, not a navigation —
 * it reports the entry as `replace`.
 */
function adoptView(next: DesignerView): void {
  view.value = next;
  designs.value = [...next.designs];
  if (!selected.value || !designs.value.some((d) => d.name === selected.value)) {
    selected.value = designs.value[0]?.name ?? null;
    reportEntry(selected.value, 'replace');
  }
}

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    adoptView(await getDesigner(props.projectId, props.folder));
    // A selected design needs a preview session before the iframe can
    // load anything — mint here so the first paint is a preview, not a
    // blank frame waiting for an async token.
    if (selected.value) {
      void ensureSession();
    }
  } catch (e) {
    error.value = t('designer.error.load', { message: (e as Error).message });
  } finally {
    loading.value = false;
  }
}

/** Public reload — the kind wrapper calls it on remote document changes. */
async function reload(): Promise<void> {
  await load();
}

async function reloadPreview(): Promise<void> {
  // Always re-mint on an explicit reload: the user expects fresh state,
  // and a stale token would fail the next sub-resource fetch anyway.
  session.value = null;
  const token = await ensureSession();
  if (token) iframeKey.value += 1;
}

/**
 * The open design is the app's sub-position — the `?entry=` contract.
 * A user click is a navigation (`push`, back undoes it); restoring one
 * from a URL is not (handled in the entry watcher, which never reports
 * back — the URL is the source there).
 */
function selectDesign(name: string): void {
  selected.value = name;
  iframeKey.value += 1;
  reportEntry(name, 'push');
}

// ── Open-design context for the chat beside the app ──────────────

// URL memory: `?entry=<design>` restores the open design (F5, shared
// links, inter-app links — the designer's AppTarget handle IS the
// design name). Without a host both sides degrade to nothing.
const { entry, report: reportEntry } = useAppEntry(() => props.documentId);

watch(entry, (value) => {
  if (value && value !== selected.value) {
    selected.value = value;
    iframeKey.value += 1;
  }
}, { immediate: true });

/**
 * What the reader has open, for the chat beside the app.
 *
 * Only the design **name** travels — the server reads title, files and
 * everything else off its own scan, exactly like the links app sends
 * only the URL. One authority for what a design is, no second copy that
 * is right until somebody edits the first.
 */
const reportAppSelection = inject<
  ((sel: {
    appDocId: string;
    selection: string;
    ref?: { label: string; vanceUri?: string; url?: string } | null;
  } | null) => void) | null
>('vance:report-app-selection', null);

watch(selectedDesign, (design) => {
  if (!reportAppSelection) return;
  if (!design) {
    reportAppSelection(null);
    return;
  }
  reportAppSelection({
    appDocId: props.documentId,
    selection: design.name,
    // The durable half: a snapshot label plus the address that re-opens
    // this design, for the day the name is gone from the catalogue.
    ref: {
      label: design.title ?? design.name,
      vanceUri: vanceRef({ path: `${props.folder}/_app.yaml`, entry: design.name }),
    },
  });
}, { immediate: true });

// Leaving the tab must retract the selection — a stale one would answer
// "this design" with a design nobody is looking at any more.
onBeforeUnmount(() => reportAppSelection?.(null));

// ── Mutations ───────────────────────────────────────────────────

/** Runs a mutation and adopts the catalogue it answers with. */
async function mutate(action: () => Promise<DesignerView>, keepSelection: string | null): Promise<void> {
  busy.value = true;
  error.value = null;
  try {
    // Keep the (possibly new) design selected — a create that lands on
    // an empty state should open the preview, not the empty state.
    if (keepSelection) selected.value = keepSelection;
    adoptView(await action());
  } catch (e) {
    error.value = t('designer.error.mutate', { message: (e as Error).message });
    // A failed reorder must not leave the drag copy diverged — resync.
    void load();
  } finally {
    busy.value = false;
  }
}

function openCreateModal(): void {
  createName.value = '';
  createTitle.value = '';
  createDescription.value = '';
  createModal.value = true;
}

async function submitCreate(): Promise<void> {
  const name = createName.value.trim();
  if (!name) return;
  createModal.value = false;
  await mutate(
    () =>
      createDesign(props.projectId, props.folder, {
        name,
        title: createTitle.value.trim() || undefined,
        description: createDescription.value.trim() || undefined,
      }),
    name,
  );
}

function openEditModal(design: DesignInfo): void {
  editName.value = design.name;
  editTitle.value = design.title ?? '';
  editDescription.value = design.description ?? '';
  editModal.value = true;
}

async function submitEdit(): Promise<void> {
  const name = editName.value;
  editModal.value = false;
  await mutate(
    () =>
      updateDesignMeta(props.projectId, props.folder, {
        name,
        title: editTitle.value.trim() || undefined,
        description: editDescription.value.trim() || undefined,
      }),
    null,
  );
}

function openDeleteModal(design: DesignInfo): void {
  deleteName.value = design.name;
  deleteModal.value = true;
}

async function submitDelete(): Promise<void> {
  const name = deleteName.value;
  deleteModal.value = false;
  if (selected.value === name) selected.value = null;
  await mutate(() => deleteDesign(props.projectId, props.folder, name), null);
}

/** The drag ended — persist the new order; the server answer resyncs. */
async function onDragEnd(): Promise<void> {
  await mutate(
    () =>
      reorderDesigns(
        props.projectId,
        props.folder,
        designs.value.map((d) => d.name),
      ),
    null,
  );
}

onMounted(load);
watch(() => [props.projectId, props.folder] as const, () => {
  session.value = null;
  void load();
});
defineExpose({ reload });
</script>

<template>
  <div class="flex flex-col h-full min-h-0">
    <!-- Toolbar -->
    <div class="flex items-center gap-3 px-4 py-2 border-b border-base-300 flex-wrap">
      <h2 class="text-lg font-semibold">{{ props.title }}</h2>
      <span
        v-if="view"
        class="text-sm text-base-content/60"
      >{{ t('designer.designs', { count: view.designs.length }) }}</span>
      <div class="flex-1" />
      <VButton
        variant="primary"
        size="sm"
        @click="openCreateModal"
      >{{ t('designer.create') }}</VButton>
      <VButton
        v-if="selectedDesign"
        variant="ghost"
        @click="reloadPreview"
      >{{ t('designer.reload') }}</VButton>
      <VButton
        variant="ghost"
        @click="load"
      >{{ t('designer.refresh') }}</VButton>
    </div>

    <VAlert v-if="error" variant="error" class="m-4">{{ error }}</VAlert>
    <VAlert v-else-if="mintError" variant="error" class="m-4">{{ mintError }}</VAlert>

    <div v-if="loading" class="p-8 text-base-content/70">{{ t('designer.loading') }}</div>

    <VEmptyState
      v-else-if="view && view.designs.length === 0"
      class="flex-1"
      :headline="t('designer.emptyHeadline')"
      :body="t('designer.emptyBody')"
    />

    <div v-else-if="view" class="flex flex-1 min-h-0">
      <!-- Design catalogue -->
      <div class="w-72 shrink-0 border-r border-base-300 overflow-y-auto p-2">
        <VueDraggable
          v-model="designs"
          :animation="150"
          item-key="name"
          handle=".designer-drag-handle"
          :disabled="busy"
          @end="onDragEnd"
        >
          <div
            v-for="design in designs"
            :key="design.name"
            class="mb-2 rounded-lg border p-3 transition-colors"
            :class="design.name === selected
              ? 'border-primary bg-primary/10'
              : 'border-base-300 hover:bg-base-200'"
          >
            <div class="flex items-start gap-1">
              <button
                type="button"
                class="designer-drag-handle flex-1 text-left"
                :title="t('designer.dragHandle')"
                @click="selectDesign(design.name)"
              >
                <div class="font-medium">{{ design.title ?? design.name }}</div>
                <div class="text-xs text-base-content/60">
                  {{ t('designer.files', { count: design.fileCount }) }}
                </div>
              </button>
              <button
                type="button"
                class="text-base-content/50 hover:text-base-content leading-none"
                :title="t('designer.editMeta')"
                @click.stop="openEditModal(design)"
              >✎</button>
              <button
                type="button"
                class="text-base-content/50 hover:text-error leading-none"
                :title="t('designer.deleteDesign')"
                @click.stop="openDeleteModal(design)"
              >✕</button>
            </div>
            <div
              v-if="design.description"
              class="text-xs text-base-content/60 mt-1 line-clamp-2"
            >{{ design.description }}</div>
          </div>
        </VueDraggable>
      </div>

      <!-- Live preview -->
      <div class="flex-1 min-w-0 flex flex-col">
        <div class="flex items-center gap-2 px-3 py-1.5 text-sm text-base-content/60 border-b border-base-300 flex-wrap">
          <span class="truncate">{{ t('designer.previewOf', { name: selectedDesign?.title ?? selectedDesign?.name }) }}</span>
          <div class="flex-1" />
          <!-- Color scheme: the iframe's color-scheme style is what the
               design's prefers-color-scheme media query sees. -->
          <div class="flex items-center rounded-lg border border-base-300 overflow-hidden">
            <button
              v-for="mode in (['auto', 'light', 'dark'] as const)"
              :key="mode"
              type="button"
              class="px-2 py-0.5"
              :class="themeMode === mode ? 'bg-primary/15 text-primary' : 'hover:bg-base-200'"
              :title="t(`designer.theme.${mode}`)"
              @click="setTheme(mode)"
            >{{ mode === 'auto' ? '◐' : mode === 'light' ? '☀' : '☾' }}</button>
          </div>
          <!-- Responsive presets. -->
          <div class="flex items-center rounded-lg border border-base-300 overflow-hidden">
            <button
              v-for="preset in (['desktop', 'tablet', 'phone'] as const)"
              :key="preset"
              type="button"
              class="px-2 py-0.5"
              :class="device === preset ? 'bg-primary/15 text-primary' : 'hover:bg-base-200'"
              :title="t(`designer.device.${preset}`)"
              @click="setDevice(preset)"
            >{{ preset === 'desktop' ? '🖥' : preset === 'tablet' ? '▭' : '▯' }}</button>
            <button
              v-if="device !== 'desktop'"
              type="button"
              class="px-2 py-0.5 border-l border-base-300"
              :class="landscape ? 'bg-primary/15 text-primary' : 'hover:bg-base-200'"
              :title="t('designer.rotate')"
              @click="toggleOrientation"
            >⟳</button>
          </div>
        </div>
        <div
          class="flex-1 min-h-0 flex justify-center"
          :class="previewLetterbox"
        >
          <iframe
            v-if="previewUrl && selectedDesign"
            :key="iframeKey"
            :src="previewUrl"
            class="h-full border-0"
            :class="device === 'desktop' ? 'w-full' : 'my-4 rounded-xl shadow-lg bg-white'"
            :style="previewStyle"
            sandbox="allow-scripts"
            referrerpolicy="no-referrer"
            :title="selectedDesign.name"
          />
        </div>
      </div>
    </div>

    <!-- Create modal -->
    <VModal v-model="createModal" :title="t('designer.createTitle')">
      <div class="flex flex-col gap-3">
        <VInput
          v-model="createName"
          :label="t('designer.nameLabel')"
          :placeholder="t('designer.namePlaceholder')"
          :help="t('designer.nameHelp')"
        />
        <VInput
          v-model="createTitle"
          :label="t('designer.titleLabel')"
          :placeholder="t('designer.titlePlaceholder')"
        />
        <VTextarea
          v-model="createDescription"
          :label="t('designer.descriptionLabel')"
          :rows="2"
        />
      </div>
      <template #actions>
        <div class="flex justify-end gap-2">
          <VButton variant="ghost" @click="createModal = false">{{ t('designer.cancel') }}</VButton>
          <VButton variant="primary" :disabled="!createName.trim()" @click="submitCreate">
            {{ t('designer.create') }}
          </VButton>
        </div>
      </template>
    </VModal>

    <!-- Edit meta modal -->
    <VModal v-model="editModal" :title="t('designer.editTitle')">
      <div class="flex flex-col gap-3">
        <VInput
          v-model="editTitle"
          :label="t('designer.titleLabel')"
          :placeholder="t('designer.titlePlaceholder')"
        />
        <VTextarea
          v-model="editDescription"
          :label="t('designer.descriptionLabel')"
          :rows="2"
        />
      </div>
      <template #actions>
        <div class="flex justify-end gap-2">
          <VButton variant="ghost" @click="editModal = false">{{ t('designer.cancel') }}</VButton>
          <VButton variant="primary" @click="submitEdit">{{ t('designer.save') }}</VButton>
        </div>
      </template>
    </VModal>

    <!-- Delete confirm modal -->
    <VModal v-model="deleteModal" :title="t('designer.deleteTitle')">
      <p>{{ t('designer.deleteConfirm', { name: deleteName }) }}</p>
      <p class="text-sm text-base-content/60 mt-1">{{ t('designer.deleteTrashNote') }}</p>
      <template #actions>
        <div class="flex justify-end gap-2">
          <VButton variant="ghost" @click="deleteModal = false">{{ t('designer.cancel') }}</VButton>
          <VButton variant="danger" @click="submitDelete">{{ t('designer.deleteDesign') }}</VButton>
        </div>
      </template>
    </VModal>
  </div>
</template>

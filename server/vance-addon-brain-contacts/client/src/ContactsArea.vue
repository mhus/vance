<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import {
  VAlert, VButton, VEmptyState, VFileInput, VGroup, VGroupRow, VIconButton, VInput, VModal, VSegment, VSelect,
  VToolbar, useConfirm,
} from '@vance/components';
import ContactAvatar from './ContactAvatar.vue';
import ContactCardView from './ContactCardView.vue';
import ContactEditor from './ContactEditor.vue';
import {
  createContact, deleteContact, exportVcf, getContact, gravatar, importVcf, listContacts, loadProjects,
  moveContact, removePhoto, saveContact, uploadPhoto,
} from './api';
import { useT } from './i18n';
import type { ContactCard } from './generated/contacts/ContactCard';
import type { ContactListView } from './generated/contacts/ContactListView';
import { ContactScope } from './generated/contacts/ContactScope';
import type { ContactView } from './generated/contacts/ContactView';

/**
 * The address book — the landing tile's area (`addon.html?addon=contacts`).
 * Left the list with search and filters, right the selected card. The area
 * keeps its state in the URL (`project`, `contact`, `scope`), so a reload or
 * a link from a `contact` document opens the same card.
 */
const t = useT();
const confirmAction = useConfirm();

type ScopeFilter = 'ALL' | ContactScope;
type Mode = 'view' | 'edit' | 'create';

const params = new URLSearchParams(window.location.search);
const projectId = ref<string | null>(params.get('project'));
const projects = ref<{ name: string; title: string }[]>([]);
const query = ref('');
const scopeFilter = ref<ScopeFilter>('ALL');
const kindFilter = ref<string>('');
const list = ref<ContactListView | null>(null);
const selected = ref<ContactView | null>(null);
const mode = ref<Mode>('view');
const busy = ref(false);
const error = ref('');
const notice = ref('');

const importOpen = ref(false);
const importScope = ref<ContactScope>(ContactScope.USER);
const importFiles = ref<File[]>([]);
const moveOpen = ref(false);
const moveTarget = ref<ContactScope | null>(null);

const projectOptions = computed(() => [
  { value: '', label: t('contacts.area.noProject') },
  ...projects.value.map((p) => ({ value: p.name, label: p.title })),
]);

const scopeOptions = computed(() => [
  { value: 'ALL' as ScopeFilter, label: t('contacts.scope.all') },
  ...(list.value?.scopes ?? []).map((s) => ({ value: s.scope as ScopeFilter, label: t(`contacts.scope.${s.scope}`) })),
]);

const kindOptions = computed(() => [
  { value: '', label: t('contacts.kind.allKinds') },
  { value: 'individual', label: t('contacts.kind.INDIVIDUAL') },
  { value: 'org', label: t('contacts.kind.ORG') },
  { value: 'group', label: t('contacts.kind.GROUP') },
]);

const writableScopes = computed(() => (list.value?.scopes ?? []).filter((s) => s.writable));

const canWriteSelected = computed(() =>
  !!selected.value && writableScopes.value.some((s) => s.scope === selected.value?.scope),
);

const moveOptions = computed(() =>
  writableScopes.value
    .filter((s) => s.scope !== selected.value?.scope)
    .map((s) => ({ value: s.scope, label: t(`contacts.scope.${s.scope}`) })),
);

function fail(e: unknown): void {
  error.value = e instanceof Error ? e.message : String(e);
}

function syncUrl(): void {
  const u = new URL(window.location.href);
  const set = (k: string, v: string | null | undefined) => (v ? u.searchParams.set(k, v) : u.searchParams.delete(k));
  set('project', projectId.value);
  set('contact', selected.value?.id);
  set('scope', selected.value?.scope.toLowerCase());
  window.history.replaceState(null, '', u.toString());
}

async function reload(): Promise<void> {
  try {
    list.value = await listContacts(projectId.value, {
      q: query.value.trim() || undefined,
      scope: scopeFilter.value === 'ALL' ? null : scopeFilter.value,
      kind: kindFilter.value || null,
    });
    if (!list.value.scopes.some((s) => s.scope === importScope.value)) {
      importScope.value = list.value.defaultScope;
    }
  } catch (e) {
    fail(e);
  }
}

async function open(id: string, scope?: ContactScope): Promise<void> {
  error.value = '';
  notice.value = '';
  try {
    selected.value = await getContact(projectId.value, id, scope);
    mode.value = 'view';
    syncUrl();
  } catch (e) {
    fail(e);
  }
}

function startCreate(): void {
  selected.value = null;
  mode.value = 'create';
  notice.value = '';
  syncUrl();
}

async function onSave(value: { card: ContactCard; scope: ContactScope }): Promise<void> {
  busy.value = true;
  error.value = '';
  try {
    selected.value = mode.value === 'create'
      ? await createContact(projectId.value, value.card, value.scope)
      : await saveContact(projectId.value, selected.value!.id, value.card, selected.value!.scope);
    mode.value = 'view';
    syncUrl();
    await reload();
  } catch (e) {
    fail(e);
  } finally {
    busy.value = false;
  }
}

async function onDelete(): Promise<void> {
  const c = selected.value;
  if (!c) return;
  const ok = await confirmAction({
    message: t('contacts.detail.confirmDelete', { name: c.card.fullName }),
    confirmLabel: t('contacts.common.delete'),
    danger: true,
  });
  if (!ok) return;
  try {
    const result = await deleteContact(projectId.value, c.id, c.scope);
    selected.value = null;
    notice.value = result.groups.length
      ? t('contacts.detail.stillInGroups', { groups: result.groups.map((g) => g.displayName).join(', ') })
      : '';
    syncUrl();
    await reload();
  } catch (e) {
    fail(e);
  }
}

async function onMove(): Promise<void> {
  const c = selected.value;
  if (!c || !moveTarget.value) return;
  try {
    const result = await moveContact(projectId.value, c.id, c.scope, moveTarget.value);
    selected.value = result.contact;
    notice.value = result.strandedGroups.length
      ? t('contacts.detail.strandedGroups', { groups: result.strandedGroups.map((g) => g.displayName).join(', ') })
      : '';
    moveOpen.value = false;
    syncUrl();
    await reload();
  } catch (e) {
    fail(e);
  }
}

const photoInput = ref<HTMLInputElement | null>(null);

async function onPhotoPicked(event: Event): Promise<void> {
  const file = (event.target as HTMLInputElement).files?.[0];
  (event.target as HTMLInputElement).value = '';
  const c = selected.value;
  if (!file || !c) return;
  try {
    selected.value = await uploadPhoto(projectId.value, c.id, c.scope, file);
    await reload();
  } catch (e) {
    fail(e);
  }
}

async function onRemovePhoto(): Promise<void> {
  const c = selected.value;
  if (!c) return;
  try {
    selected.value = await removePhoto(projectId.value, c.id, c.scope);
    await reload();
  } catch (e) {
    fail(e);
  }
}

async function onGravatar(): Promise<void> {
  const c = selected.value;
  if (!c) return;
  try {
    const result = await gravatar(projectId.value, c.id, c.scope);
    if (result.found && result.contact) {
      selected.value = result.contact;
      await reload();
    } else {
      notice.value = t('contacts.detail.gravatarNone');
    }
  } catch (e) {
    fail(e);
  }
}

async function onImport(): Promise<void> {
  const file = importFiles.value[0];
  if (!file) return;
  busy.value = true;
  try {
    const report = await importVcf(projectId.value, importScope.value, file);
    const lines = [t('contacts.area.imported', { created: report.created, updated: report.updated })];
    if (report.skipped.length) lines.push(`${t('contacts.area.importSkipped')} ${report.skipped.join('; ')}`);
    if (report.warnings.length) lines.push(`${t('contacts.area.importWarnings')} ${report.warnings.join('; ')}`);
    notice.value = lines.join('\n');
    importOpen.value = false;
    importFiles.value = [];
    await reload();
  } catch (e) {
    fail(e);
  } finally {
    busy.value = false;
  }
}

async function onExport(): Promise<void> {
  try {
    const ids = selected.value ? [selected.value.id] : (list.value?.entries ?? []).map((e) => e.id);
    const { blob, filename } = await exportVcf(projectId.value, ids);
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename ?? 'contacts.vcf';
    a.click();
    URL.revokeObjectURL(url);
  } catch (e) {
    fail(e);
  }
}

let searchTimer: ReturnType<typeof setTimeout> | undefined;
watch(query, () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => void reload(), 250);
});
watch([scopeFilter, kindFilter], () => void reload());
watch(projectId, async () => {
  selected.value = null;
  scopeFilter.value = 'ALL';
  syncUrl();
  await reload();
});

onMounted(async () => {
  void loadProjects().then((p) => (projects.value = p)).catch(() => undefined);
  await reload();
  const id = params.get('contact');
  const scope = params.get('scope')?.toUpperCase() as ContactScope | undefined;
  if (id) await open(id, scope);
});

const projectSelect = computed({
  get: () => projectId.value ?? '',
  set: (v: string) => (projectId.value = v || null),
});
</script>

<template>
  <div class="flex flex-col gap-4">
    <VToolbar>
      <div class="w-56" :title="t('contacts.area.project')"><VSelect v-model="projectSelect" :options="projectOptions" /></div>
      <div class="w-72"><VInput v-model="query" :placeholder="t('contacts.area.search')" size="sm" /></div>
      <VSegment v-model="scopeFilter" :options="scopeOptions" />
      <div class="w-40"><VSelect v-model="kindFilter" :options="kindOptions" /></div>
      <template #end>
        <VIconButton icon="upload" :label="t('contacts.area.import')" @click="importOpen = true" />
        <VIconButton icon="download" :label="t('contacts.area.export')" @click="onExport" />
        <VButton variant="primary" icon="add" :disabled="writableScopes.length === 0" @click="startCreate">
          {{ t('contacts.area.add') }}
        </VButton>
      </template>
    </VToolbar>

    <VAlert v-if="error" variant="error">{{ error }}</VAlert>
    <VAlert v-if="notice" variant="info"><span class="whitespace-pre-line">{{ notice }}</span></VAlert>
    <VAlert v-if="list && list.broken.length" variant="warning">
      {{ t('contacts.area.broken', { n: list.broken.length }, list.broken.length) }}: {{ list.broken.join(', ') }}
    </VAlert>

    <div class="grid grid-cols-1 md:grid-cols-[22rem_1fr] gap-6 items-start">
      <div class="flex flex-col gap-2 min-w-0">
        <p v-if="list" class="px-1 text-xs text-(--v-label-secondary)">
          {{ t('contacts.area.count', { n: list.entries.length }, list.entries.length) }}
        </p>
        <VGroup v-if="list && list.entries.length">
          <div
            v-for="e in list.entries"
            :key="`${e.scope}:${e.id}`"
            :class="selected && selected.id === e.id && selected.scope === e.scope ? 'bg-(--v-selection)' : ''"
          >
            <VGroupRow navigates @click="open(e.id, e.scope)">
              <template #label>
                <span class="flex items-center gap-3 min-w-0">
                  <ContactAvatar
                    :name="e.displayName"
                    :project-id="projectId"
                    :id="e.id"
                    :scope="e.scope"
                    :has-photo="e.hasPhoto"
                    :photo-version="e.photoVersion"
                    size="sm"
                  />
                  <span class="truncate">{{ e.displayName }}</span>
                </span>
              </template>
              <template #description>
                {{ [e.org, e.email ?? e.phone].filter(Boolean).join(' · ') }}
              </template>
              <span class="text-xs text-(--v-label-tertiary)">{{ t(`contacts.scope.${e.scope}`) }}</span>
            </VGroupRow>
          </div>
        </VGroup>
        <VEmptyState
          v-else-if="list"
          :headline="query || scopeFilter !== 'ALL' || kindFilter ? t('contacts.area.noMatch') : t('contacts.area.empty')"
          :body="query ? undefined : t('contacts.area.emptyBody')"
        />
      </div>

      <div class="min-w-0">
        <ContactEditor
          v-if="mode === 'create'"
          :key="'create'"
          :card="null"
          :scopes="list?.scopes ?? []"
          :scope="null"
          :default-scope="list?.defaultScope ?? ContactScope.USER"
          :busy="busy"
          @save="onSave"
          @cancel="mode = 'view'"
        />
        <ContactEditor
          v-else-if="mode === 'edit' && selected"
          :key="`edit:${selected.id}`"
          :card="selected.card"
          :scopes="list?.scopes ?? []"
          :scope="selected.scope"
          :default-scope="selected.scope"
          :busy="busy"
          @save="onSave"
          @cancel="mode = 'view'"
        />
        <template v-else-if="selected">
          <VToolbar class="mb-4">
            <span class="text-xs text-(--v-label-secondary)">{{ t(`contacts.scope.hint${selected.scope}`) }}</span>
            <template #end>
              <template v-if="canWriteSelected">
                <VIconButton icon="edit" :label="t('contacts.detail.edit')" @click="mode = 'edit'" />
                <VIconButton icon="image" :label="t('contacts.detail.photo')" @click="photoInput?.click()" />
                <VIconButton
                  v-if="selected.card.emails.length"
                  icon="user"
                  :label="t('contacts.detail.gravatar')"
                  @click="onGravatar"
                />
                <VIconButton
                  v-if="selected.hasPhoto"
                  icon="eraser"
                  :label="t('contacts.detail.removePhoto')"
                  @click="onRemovePhoto"
                />
                <VIconButton
                  v-if="moveOptions.length"
                  icon="share"
                  :label="t('contacts.detail.move')"
                  @click="moveTarget = moveOptions[0].value; moveOpen = true"
                />
                <VIconButton icon="delete" :label="t('contacts.detail.delete')" danger @click="onDelete" />
              </template>
            </template>
          </VToolbar>
          <input ref="photoInput" type="file" accept="image/*" class="hidden" @change="onPhotoPicked" />
          <ContactCardView
            :card="selected.card"
            :project-id="projectId"
            :id="selected.id"
            :scope="selected.scope"
            :has-photo="selected.hasPhoto"
            :photo-version="selected.photoVersion"
            :members="selected.members"
            :groups="selected.groups"
            @open="(id) => open(id)"
          />
          <p v-if="!canWriteSelected" class="mt-4 text-xs text-(--v-label-secondary)">{{ t('contacts.detail.readOnly') }}</p>
        </template>
        <p v-else class="p-8 text-sm text-(--v-label-secondary)">{{ t('contacts.area.select') }}</p>
      </div>
    </div>

    <VModal v-model="importOpen" :title="t('contacts.area.importTitle')" size="sm">
      <form id="contact-import" class="flex flex-col gap-3" @submit.prevent="onImport">
        <VSelect
          v-model="importScope"
          :options="writableScopes.map((s) => ({ value: s.scope, label: t(`contacts.scope.${s.scope}`) }))"
          :label="t('contacts.area.importInto')"
        />
        <VFileInput v-model="importFiles" accept=".vcf,text/vcard" :label="t('contacts.area.importFile')" />
      </form>
      <template #actions>
        <VButton variant="secondary" @click="importOpen = false">{{ t('contacts.common.cancel') }}</VButton>
        <VButton variant="primary" type="submit" form="contact-import" :loading="busy" :disabled="importFiles.length === 0">
          {{ t('contacts.area.import') }}
        </VButton>
      </template>
    </VModal>

    <VModal v-model="moveOpen" :title="t('contacts.detail.moveTitle')" :description="t('contacts.detail.moveBody')" size="sm">
      <VSelect v-model="moveTarget" :options="moveOptions" :label="t('contacts.detail.move')" />
      <template #actions>
        <VButton variant="secondary" @click="moveOpen = false">{{ t('contacts.common.cancel') }}</VButton>
        <VButton variant="primary" @click="onMove">{{ t('contacts.detail.move') }}</VButton>
      </template>
    </VModal>
  </div>
</template>

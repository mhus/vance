<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VButton, VIconButton, VModal } from '@/components';
import { useDocumentArchives } from '@/composables/useDocumentArchives';
import type { DocumentArchiveSummary, DocumentDto } from '@vance/generated';
import DocumentVersionDiff from './DocumentVersionDiff.vue';
import { canDiffArchive, loadArchiveDiff, type ArchiveDiff } from './archiveDiff';

/**
 * Versions panel slotted into the document detail view. Shows the count
 * (always visible as a header), expands into a date-sorted list, lets
 * the user preview / compare / delete / restore individual versions.
 * Compare is offered for textual mime types only — never for age-encrypted
 * documents — and diffs the version against the current content.
 *
 * <p>Read-only — there is no "edit this archived version" path. Restoring
 * brings the version back as the live document; the previous live content
 * becomes a new archive entry, so the user never loses their current work
 * accidentally.
 */
const props = defineProps<{
  /** Live document the panel is attached to. {@code null} clears the list. */
  document: DocumentDto | null;
  /**
   * Current body as the editor holds it — the "new" side of the version
   * diff, so unsaved edits are part of the comparison. When absent the
   * stored live body is fetched instead.
   */
  currentText?: string;
  /** True when {@link currentText} carries edits not yet saved. */
  currentDirty?: boolean;
  /**
   * True for an age-encrypted document whose {@link document} carries the
   * decrypted (inner) kind and mime — the panel cannot tell from those.
   */
  encrypted?: boolean;
}>();

const emit = defineEmits<{
  /** Fired after a successful restore — parent reloads the live document. */
  (e: 'restored', restored: DocumentDto): void;
  /** Fired after a "restore into a new file" — parent may open / list it. */
  (e: 'created', created: DocumentDto): void;
  /** Fired whenever the archive count for the current document changes.
   *  Lets the parent surface a counter outside the panel (e.g. a badge
   *  in the metadata strip) without owning the archive state. */
  (e: 'update:count', count: number): void;
}>();

const { t } = useI18n();
const archives = useDocumentArchives();

// Two-step destructive actions — same posture as the document-delete
// confirmation modal in DocumentApp.vue.
const confirmDelete = ref<string | null>(null);
const confirmRestore = ref<string | null>(null);
const confirmCopy = ref<string | null>(null);
const acting = ref(false);

// Transient feedback for the manual "create version now" action (e.g. the
// "no changes, nothing to snapshot" case where no list row appears).
const flash = ref<string | null>(null);

// Version compare: archived body (old) against the current content (new).
const diff = ref<ArchiveDiff | null>(null);
const diffLoading = ref<string | null>(null);

watch(
  () => props.document?.id,
  async (newId) => {
    flash.value = null;
    // A comparison belongs to the document it was opened for.
    diff.value = null;
    if (!newId) {
      archives.items.value = [];
      archives.clearPreview();
      return;
    }
    await archives.load(newId);
  },
  { immediate: true },
);

const count = computed(() => archives.items.value.length);

// Surface the count to the parent so it can render a badge or
// counter without owning the archive state. Initial mount fires once
// with the current length (typically 0 before {@link load} resolves);
// subsequent emits cover load results, delete and restore.
watch(count, (n) => emit('update:count', n), { immediate: true });

function canDiff(archive: DocumentArchiveSummary): boolean {
  return canDiffArchive(archive, props.document, props.encrypted === true);
}

async function openDiff(archive: DocumentArchiveSummary): Promise<void> {
  if (!props.document) return;
  const documentId = props.document.id;
  diffLoading.value = archive.id;
  try {
    const opened = await loadArchiveDiff(
      archives,
      documentId,
      archive,
      props.currentText,
      () => props.document?.id === documentId,
    );
    if (opened) diff.value = opened;
  } finally {
    diffLoading.value = null;
  }
}

function formatDate(ms: number): string {
  return new Date(ms).toLocaleString();
}

async function openPreview(archiveId: string): Promise<void> {
  if (!props.document) return;
  await archives.open(props.document.id, archiveId);
}

async function deleteArchive(): Promise<void> {
  if (!props.document || !confirmDelete.value) return;
  acting.value = true;
  try {
    const ok = await archives.remove(props.document.id, confirmDelete.value);
    if (ok) confirmDelete.value = null;
  } finally {
    acting.value = false;
  }
}

async function createVersion(): Promise<void> {
  if (!props.document) return;
  acting.value = true;
  flash.value = null;
  try {
    const outcome = await archives.createNow(props.document.id);
    if (outcome === 'created') {
      flash.value = t('documents.archives.createdOk');
    } else if (outcome === 'unchanged') {
      flash.value = t('documents.archives.createdUnchanged');
    } else if (outcome === 'disabled') {
      flash.value = t('documents.archives.createdDisabled');
    }
  } finally {
    acting.value = false;
  }
}

async function restoreArchive(): Promise<void> {
  if (!props.document || !confirmRestore.value) return;
  acting.value = true;
  try {
    const restored = await archives.restore(props.document.id, confirmRestore.value);
    if (restored) {
      confirmRestore.value = null;
      archives.clearPreview();
      emit('restored', restored);
    }
  } finally {
    acting.value = false;
  }
}

async function copyToNewFile(): Promise<void> {
  if (!props.document || !confirmCopy.value) return;
  acting.value = true;
  flash.value = null;
  try {
    const created = await archives.restoreToNewFile(props.document.id, confirmCopy.value);
    if (created) {
      confirmCopy.value = null;
      archives.clearPreview();
      flash.value = t('documents.archives.copiedOk', { path: created.path });
      emit('created', created);
    }
  } finally {
    acting.value = false;
  }
}
</script>

<template>
  <div class="mt-3 border border-(--v-separator) rounded-(--v-radius-group) overflow-hidden">
    <div
      class="flex items-center justify-between px-3 py-2 border-b border-(--v-separator)
             text-xs uppercase text-(--v-label-secondary)"
    >
      <span>
        {{ $t('documents.archives.heading') }}
        <span class="ml-2 font-mono normal-case">{{ count }}</span>
      </span>
    </div>

    <div class="p-3 flex flex-col gap-3">
      <VAlert v-if="archives.error.value" variant="error">
        <span>{{ archives.error.value }}</span>
      </VAlert>

      <div class="flex items-center justify-between gap-3">
        <VButton
          size="sm"
          variant="secondary"
          :loading="acting"
          :disabled="acting || !document"
          @click="createVersion"
        >
          {{ $t('documents.archives.createNow') }}
        </VButton>
        <span v-if="flash" class="text-xs text-(--v-label-secondary)">{{ flash }}</span>
      </div>

      <p v-if="!archives.loading.value && count === 0" class="text-sm text-(--v-label-secondary)">
        {{ $t('documents.archives.empty') }}
      </p>

      <ul
        v-if="count > 0"
        class="flex flex-col divide-y divide-(--v-separator) min-h-24 max-h-48 overflow-y-auto"
      >
        <li
          v-for="archive in archives.items.value"
          :key="archive.id"
          class="py-2 flex items-center justify-between gap-3"
        >
          <button
            type="button"
            class="flex-1 min-w-0 text-left flex flex-col gap-0.5 hover:underline cursor-pointer"
            @click="openPreview(archive.id)"
          >
            <span class="text-sm font-mono">{{ formatDate(archive.archivedAtMs) }}</span>
            <span class="text-xs text-(--v-label-secondary) truncate">
              {{ archive.path }}
              <template v-if="archive.size">
                · {{ archive.size }} B
              </template>
            </span>
          </button>
          <div class="flex gap-1 shrink-0">
            <VIconButton
              v-if="canDiff(archive)"
              icon="diff"
              size="sm"
              :label="$t('documents.archives.compare')"
              :disabled="acting || diffLoading !== null"
              @click="openDiff(archive)"
            />
            <VIconButton
              icon="restore"
              size="sm"
              :label="$t('documents.archives.restore')"
              :disabled="acting"
              @click="confirmRestore = archive.id"
            />
            <VIconButton
              icon="copy"
              size="sm"
              :label="$t('documents.archives.restoreCopy')"
              :disabled="acting"
              @click="confirmCopy = archive.id"
            />
            <VIconButton
              icon="delete"
              size="sm"
              danger
              :label="$t('documents.archives.delete')"
              :disabled="acting"
              @click="confirmDelete = archive.id"
            />
          </div>
        </li>
      </ul>
    </div>

    <!-- Preview modal — read-only display of the archived body. -->
    <VModal
      :model-value="archives.preview.value !== null"
      :title="$t('documents.archives.previewTitle', {
        when: archives.preview.value ? formatDate(archives.preview.value.archivedAtMs) : '',
      })"
      :description="archives.preview.value
        ? [archives.preview.value.path, archives.preview.value.mimeType].filter(Boolean).join(' · ')
        : undefined"
      size="lg"
      @update:model-value="(v) => v || archives.clearPreview()"
    >
      <template v-if="archives.preview.value">
        <pre
          v-if="archives.preview.value.inline && archives.preview.value.inlineText !== undefined"
          class="text-xs whitespace-pre-wrap font-mono bg-(--v-fill) p-2 rounded-(--v-radius-control)"
        >{{ archives.preview.value.inlineText }}</pre>
        <p v-else class="text-sm text-(--v-label-secondary)">
          {{ $t('documents.archives.previewBinary') }}
        </p>
      </template>
      <template #actions>
        <VButton variant="secondary" @click="archives.clearPreview()">
          {{ $t('documents.archives.close') }}
        </VButton>
      </template>
    </VModal>

    <!-- Diff modal — archived version against the current content. -->
    <VModal
      :model-value="diff !== null"
      :title="$t('documents.archives.diffTitle', {
        when: diff ? formatDate(diff.archive.archivedAtMs) : '',
      })"
      :description="currentDirty
        ? $t('documents.archives.diffDescriptionUnsaved')
        : $t('documents.archives.diffDescription')"
      size="xl"
      @update:model-value="(v) => v || (diff = null)"
    >
      <DocumentVersionDiff v-if="diff" :old-text="diff.oldText" :new-text="diff.newText" />
      <template #actions>
        <VButton variant="secondary" @click="diff = null">
          {{ $t('documents.archives.close') }}
        </VButton>
      </template>
    </VModal>

    <!-- Confirm restore. -->
    <VModal
      :model-value="confirmRestore !== null"
      :title="$t('documents.archives.confirmRestoreTitle')"
      size="sm"
      @update:model-value="(v) => v || (confirmRestore = null)"
    >
      <p class="text-sm">{{ $t('documents.archives.confirmRestoreBody') }}</p>
      <template #actions>
        <VButton variant="secondary" :disabled="acting" @click="confirmRestore = null">
          {{ $t('documents.archives.cancel') }}
        </VButton>
        <VButton variant="primary" :loading="acting" @click="restoreArchive">
          {{ $t('documents.archives.restore') }}
        </VButton>
      </template>
    </VModal>

    <!-- Confirm restore into a new file. -->
    <VModal
      :model-value="confirmCopy !== null"
      :title="$t('documents.archives.confirmCopyTitle')"
      size="sm"
      @update:model-value="(v) => v || (confirmCopy = null)"
    >
      <p class="text-sm">{{ $t('documents.archives.confirmCopyBody') }}</p>
      <template #actions>
        <VButton variant="secondary" :disabled="acting" @click="confirmCopy = null">
          {{ $t('documents.archives.cancel') }}
        </VButton>
        <VButton variant="primary" :loading="acting" @click="copyToNewFile">
          {{ $t('documents.archives.restoreCopy') }}
        </VButton>
      </template>
    </VModal>

    <!-- Confirm delete. -->
    <VModal
      :model-value="confirmDelete !== null"
      :title="$t('documents.archives.confirmDeleteTitle')"
      size="sm"
      @update:model-value="(v) => v || (confirmDelete = null)"
    >
      <p class="text-sm">{{ $t('documents.archives.confirmDeleteBody') }}</p>
      <template #actions>
        <VButton variant="secondary" :disabled="acting" @click="confirmDelete = null">
          {{ $t('documents.archives.cancel') }}
        </VButton>
        <VButton variant="danger" filled :loading="acting" @click="deleteArchive">
          {{ $t('documents.archives.delete') }}
        </VButton>
      </template>
    </VModal>
  </div>
</template>

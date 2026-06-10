<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { VAlert, VButton, VModal } from '@/components';
import { useDocumentArchives } from '@/composables/useDocumentArchives';
import type { DocumentDto } from '@vance/generated';

/**
 * Versions panel slotted into the document detail view. Shows the count
 * (always visible as a header), expands into a date-sorted list, lets
 * the user preview / delete / restore individual versions.
 *
 * <p>Read-only — there is no "edit this archived version" path. Restoring
 * brings the version back as the live document; the previous live content
 * becomes a new archive entry, so the user never loses their current work
 * accidentally.
 */
const props = defineProps<{
  /** Live document the panel is attached to. {@code null} clears the list. */
  document: DocumentDto | null;
}>();

const emit = defineEmits<{
  /** Fired after a successful restore — parent reloads the live document. */
  (e: 'restored', restored: DocumentDto): void;
  /** Fired whenever the archive count for the current document changes.
   *  Lets the parent surface a counter outside the panel (e.g. a badge
   *  in the metadata strip) without owning the archive state. */
  (e: 'update:count', count: number): void;
}>();

const archives = useDocumentArchives();
const expanded = ref(false);

// Two-step destructive actions — same posture as the document-delete
// confirmation modal in DocumentApp.vue.
const confirmDelete = ref<string | null>(null);
const confirmRestore = ref<string | null>(null);
const acting = ref(false);

watch(
  () => props.document?.id,
  async (newId) => {
    if (!newId) {
      archives.items.value = [];
      archives.clearPreview();
      expanded.value = false;
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
</script>

<template>
  <div class="mt-3 border border-base-300 rounded-md overflow-hidden">
    <button
      type="button"
      class="w-full flex items-center justify-between px-3 py-2 bg-base-200 text-xs uppercase opacity-70 hover:opacity-100 transition cursor-pointer"
      @click="expanded = !expanded"
    >
      <span>
        {{ $t('documents.archives.heading') }}
        <span class="ml-2 font-mono normal-case opacity-100">{{ count }}</span>
      </span>
      <span aria-hidden="true">{{ expanded ? '▾' : '▸' }}</span>
    </button>

    <div v-if="expanded" class="p-3 flex flex-col gap-3">
      <VAlert v-if="archives.error.value" variant="error">
        <span>{{ archives.error.value }}</span>
      </VAlert>

      <p v-if="!archives.loading.value && count === 0" class="text-sm italic opacity-60">
        {{ $t('documents.archives.empty') }}
      </p>

      <ul
        v-if="count > 0"
        class="flex flex-col divide-y divide-base-300 max-h-80 overflow-y-auto"
      >
        <li
          v-for="archive in archives.items.value"
          :key="archive.id"
          class="py-2 flex items-center justify-between gap-3"
        >
          <button
            type="button"
            class="flex-1 text-left flex flex-col gap-0.5 hover:underline cursor-pointer"
            @click="openPreview(archive.id)"
          >
            <span class="text-sm font-mono">{{ formatDate(archive.archivedAtMs) }}</span>
            <span class="text-xs opacity-70 truncate">
              {{ archive.path }}
              <template v-if="archive.size">
                · {{ archive.size }} B
              </template>
            </span>
          </button>
          <div class="flex gap-1 shrink-0">
            <VButton
              size="sm"
              variant="ghost"
              :disabled="acting"
              @click="confirmRestore = archive.id"
            >
              {{ $t('documents.archives.restore') }}
            </VButton>
            <VButton
              size="sm"
              variant="ghost"
              :disabled="acting"
              @click="confirmDelete = archive.id"
            >
              {{ $t('documents.archives.delete') }}
            </VButton>
          </div>
        </li>
      </ul>
    </div>

    <!-- Preview modal — read-only display of the archived body. -->
    <VModal
      :model-value="archives.preview.value !== null"
      @update:model-value="(v) => v || archives.clearPreview()"
    >
      <template #title>
        {{ $t('documents.archives.previewTitle', {
          when: archives.preview.value ? formatDate(archives.preview.value.archivedAtMs) : '',
        }) }}
      </template>
      <div v-if="archives.preview.value" class="flex flex-col gap-2 max-h-[60vh] overflow-y-auto">
        <div class="text-xs opacity-70 font-mono">
          {{ archives.preview.value.path }}
          <template v-if="archives.preview.value.mimeType">
            · {{ archives.preview.value.mimeType }}
          </template>
        </div>
        <pre
          v-if="archives.preview.value.inline && archives.preview.value.inlineText !== undefined"
          class="text-xs whitespace-pre-wrap font-mono bg-base-200 p-2 rounded-md"
        >{{ archives.preview.value.inlineText }}</pre>
        <p v-else class="text-sm italic opacity-60">
          {{ $t('documents.archives.previewBinary') }}
        </p>
      </div>
      <template #footer>
        <VButton variant="ghost" @click="archives.clearPreview()">
          {{ $t('documents.archives.close') }}
        </VButton>
      </template>
    </VModal>

    <!-- Confirm restore. -->
    <VModal
      :model-value="confirmRestore !== null"
      @update:model-value="(v) => v || (confirmRestore = null)"
    >
      <template #title>{{ $t('documents.archives.confirmRestoreTitle') }}</template>
      <p class="text-sm">{{ $t('documents.archives.confirmRestoreBody') }}</p>
      <template #footer>
        <VButton variant="ghost" :disabled="acting" @click="confirmRestore = null">
          {{ $t('documents.archives.cancel') }}
        </VButton>
        <VButton variant="primary" :loading="acting" @click="restoreArchive">
          {{ $t('documents.archives.restore') }}
        </VButton>
      </template>
    </VModal>

    <!-- Confirm delete. -->
    <VModal
      :model-value="confirmDelete !== null"
      @update:model-value="(v) => v || (confirmDelete = null)"
    >
      <template #title>{{ $t('documents.archives.confirmDeleteTitle') }}</template>
      <p class="text-sm">{{ $t('documents.archives.confirmDeleteBody') }}</p>
      <template #footer>
        <VButton variant="ghost" :disabled="acting" @click="confirmDelete = null">
          {{ $t('documents.archives.cancel') }}
        </VButton>
        <VButton variant="danger" :loading="acting" @click="deleteArchive">
          {{ $t('documents.archives.delete') }}
        </VButton>
      </template>
    </VModal>
  </div>
</template>

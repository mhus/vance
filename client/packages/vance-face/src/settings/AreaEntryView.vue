<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VButton, CodeEditor } from '@/components';
import { brainFetch, brainFetchText, RestError } from '@vance/shared';
import { resolveKind } from '@vance/kind-registry';
import type { DocumentDto, DocumentUpdateRequest } from '@vance/generated';

/**
 * Lean inline host for one settings-area entry — the Settings page's
 * counterpart of the Cortex document tab, minus everything document-tab
 * specific (notes, properties, locks, archives). Load → parse → render
 * the kind's own view → serialize → save, over the standard documents
 * REST. Kinds without a view/codec (or bodies that fail to parse) fall
 * back to the raw CodeEditor — the same rescue path the Cortex shell
 * takes, so an area entry is never a dead end.
 */

const props = defineProps<{
  /** Document id of the entry. */
  documentId: string;
  /** Kind id the row belongs to — the GET dto carries no kind metadata. */
  kindId: string;
}>();

const { t } = useI18n();

const loading = ref(false);
const loadError = ref<string | null>(null);
const saveError = ref<string | null>(null);
const saving = ref(false);
const saved = ref(false);

const doc = ref<DocumentDto | null>(null);
/** Raw inline text of the loaded document — the save target either way. */
const rawText = ref('');
/** Typed model when the kind has a codec; {@code null} in raw fallback. */
const model = ref<unknown | null>(null);
const parseFailed = ref(false);

const kindEntry = computed(() => resolveKind(props.kindId));

/** Typed mode requires view AND codec — anything less is the raw fallback. */
const typedMode = computed(() => {
  const entry = kindEntry.value;
  return !parseFailed.value
    && entry != null
    && entry.view != null
    && entry.parse != null
    && entry.serialize != null
    && model.value != null;
});

async function load(): Promise<void> {
  loading.value = true;
  loadError.value = null;
  parseFailed.value = false;
  model.value = null;
  try {
    const dto = await brainFetch<DocumentDto>(
      'GET', `documents/${encodeURIComponent(props.documentId)}`);
    // Storage-backed documents (every REST-created/updated doc is) carry
    // no inlineText — the body streams from the content endpoint, the
    // same path the Cortex document tab takes.
    const text = dto.inlineText
      ?? (await brainFetchText(`documents/${encodeURIComponent(props.documentId)}/content`))
      ?? '';
    doc.value = dto;
    rawText.value = text;
    const entry = kindEntry.value;
    if (entry?.parse) {
      try {
        model.value = entry.parse(text, dto.mimeType ?? '');
      } catch {
        // A body the codec rejects is a rescue case, not a failure — the
        // raw editor below lets the user fix it in place.
        parseFailed.value = true;
      }
    }
  } catch (err) {
    loadError.value = err instanceof RestError ? err.message : String(err);
    doc.value = null;
  } finally {
    loading.value = false;
  }
}

async function save(): Promise<void> {
  if (!doc.value) return;
  saving.value = true;
  saveError.value = null;
  saved.value = false;
  try {
    let text = rawText.value;
    if (typedMode.value) {
      text = kindEntry.value!.serialize!(model.value, doc.value.mimeType ?? '');
    }
    const body: DocumentUpdateRequest = { inlineText: text };
    await brainFetch<DocumentDto>(
      'PUT',
      `documents/${encodeURIComponent(props.documentId)}`,
      { body },
    );
    rawText.value = text;
    saved.value = true;
    // Reset the typed model from the persisted state so a follow-up edit
    // diffs against what is actually stored.
    const entry = kindEntry.value;
    if (entry?.parse) {
      try {
        model.value = entry.parse(text, doc.value.mimeType ?? '');
      } catch {
        parseFailed.value = true;
      }
    }
  } catch (err) {
    saveError.value = err instanceof RestError ? err.message : String(err);
  } finally {
    saving.value = false;
  }
}

watch(
  () => [props.documentId, props.kindId] as const,
  () => { void load(); },
  { immediate: true },
);
</script>

<template>
  <div class="flex flex-col gap-3">
    <VAlert v-if="loadError" variant="error">{{ loadError }}</VAlert>
    <VAlert v-else-if="saveError" variant="error">{{ saveError }}</VAlert>
    <VAlert v-else-if="saved" variant="success">{{ t('settings.areas.entrySaved') }}</VAlert>

    <div v-if="loading" class="opacity-60 text-sm">{{ t('common.loading') }}</div>

    <template v-else-if="doc">
      <div class="flex items-center justify-between gap-2">
        <div class="font-mono text-xs opacity-60 truncate">{{ doc.path }}</div>
        <VButton
          variant="primary"
          size="sm"
          :loading="saving"
          @click="save"
        >{{ t('settings.areas.save') }}</VButton>
      </div>

      <!-- Typed mode: the kind's own view, model in, updated model out. -->
      <component
        :is="kindEntry!.view"
        v-if="typedMode"
        :key="doc.id"
        :doc="model"
        :project-id="doc.projectId"
        :doc-path="doc.path"
        @update:doc="model = $event"
      />

      <!-- Raw fallback: no view/codec, or the codec rejected the body. -->
      <template v-else>
        <p v-if="parseFailed" class="text-xs text-warning">
          {{ t('settings.areas.parseFailedRaw') }}
        </p>
        <CodeEditor
          v-model="rawText"
          :mime-type="doc.mimeType"
          :rows="24"
        />
      </template>
    </template>
  </div>
</template>

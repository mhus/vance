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
 *
 * <p>Two save verbs, deliberately: <b>Anwenden</b> persists and stays
 * (keep editing, try again), <b>Speichern</b> persists and emits
 * {@code close} — the host clears the {@code entry} URL param and the
 * list is back. A third control, the Cortex jump link, hands the very
 * same document to the full editor: settings configures, Cortex works.
 */

const props = defineProps<{
  /** Document id of the entry. */
  documentId: string;
  /** Kind id the row belongs to — the GET dto carries no kind metadata. */
  kindId: string;
}>();

const emit = defineEmits<{
  /** Saved via the Speichern verb — the host navigates back to the list. */
  close: [];
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

/** Deep link into Cortex — the same document in the full editor. */
const cortexHref = computed<string>(() => doc.value
  ? `/cortex?project=${encodeURIComponent(doc.value.projectId)}`
    + `&path=${encodeURIComponent(doc.value.path)}`
  : '#');

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

/** Persists the current state and reports whether it landed. */
async function save(): Promise<boolean> {
  if (!doc.value) return false;
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
    return true;
  } catch (err) {
    saveError.value = err instanceof RestError ? err.message : String(err);
    return false;
  } finally {
    saving.value = false;
  }
}

/** Anwenden — persist and stay: keep editing, try again. */
async function apply(): Promise<void> {
  await save();
}

/** Speichern — persist and hand the navigation back to the host. */
async function saveAndClose(): Promise<void> {
  if (await save()) emit('close');
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
        <div class="flex items-center gap-2">
          <!-- Jump link: same document, full editor. Settings configures,
               Cortex works — a handoff, not a second editor instance. -->
          <a
            class="text-xs underline-offset-2 hover:underline opacity-60 hover:opacity-100"
            :href="cortexHref"
            :title="t('settings.areas.openInCortex')"
          >↗</a>
          <VButton
            variant="secondary"
            outline
            size="sm"
            :loading="saving"
            @click="apply"
          >{{ t('settings.areas.apply') }}</VButton>
          <VButton
            variant="primary"
            size="sm"
            :loading="saving"
            @click="saveAndClose"
          >{{ t('settings.areas.save') }}</VButton>
        </div>
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

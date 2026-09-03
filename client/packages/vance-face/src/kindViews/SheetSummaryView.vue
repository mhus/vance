<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { VButton, VModal } from '@/components';
import SheetView from './SheetView.vue';
import { parseSheet, serializeSheet, type SheetDocument } from '@vance/shared';
import { useDocuments } from '@/composables/useDocuments';

/**
 * Embedded (chat / inline-embed) renderer for `kind: sheet`: a compact,
 * read-only preview card with a "Bearbeiten" dialog that opens the full
 * SheetView editor. Mirrors the finance-tree summary pattern — authoring
 * still happens in the Cortex kind editor; this is the data-only channel.
 */
const props = defineProps<{
  mode?: string;
  document?: {
    id?: string;
    projectId?: string;
    path?: string;
    inlineText?: string | null;
    mimeType?: string | null;
    title?: string | null;
  } | null;
  embedRef?: { project?: string; path?: string } | null;
}>();

const projectId = computed(() => props.document?.projectId ?? props.embedRef?.project ?? '');
const path = computed(() => props.document?.path ?? props.embedRef?.path ?? '');
const mime = computed(() => props.document?.mimeType ?? 'application/json');
const editOpen = ref(false);

const model = ref<SheetDocument | null>(parseBody(props.document?.inlineText ?? ''));
watch(() => props.document?.inlineText, (t) => { model.value = parseBody(t ?? ''); });

function parseBody(body: string): SheetDocument | null {
  if (!body) return null;
  try {
    return parseSheet(body, mime.value);
  } catch {
    return null;
  }
}

const PREVIEW_ROWS = 6;
const PREVIEW_COLS = 5;
const previewCols = computed(() => (model.value?.schema ?? []).slice(0, PREVIEW_COLS));
const cellMap = computed(() => {
  const m = new Map<string, string>();
  for (const c of model.value?.cells ?? []) m.set(c.field, c.data);
  return m;
});
const previewRowCount = computed(() =>
  Math.min(PREVIEW_ROWS, Math.max(1, model.value?.rows ?? PREVIEW_ROWS)));

function display(col: string, row: number): string {
  const d = cellMap.value.get(col + row) ?? '';
  if (d.startsWith('=')) {
    const v = model.value?.computed?.values.find((x) => x.field === col + row);
    return v ? v.value : d;
  }
  return d;
}

const dims = computed(() => ({
  cols: model.value?.schema.length ?? 0,
  rows: model.value?.rows ?? 0,
}));

const docs = useDocuments();
async function onEdit(next: SheetDocument): Promise<void> {
  model.value = next;
  if (!props.document?.id) return;
  const body = serializeSheet(next, mime.value);
  await docs.replaceContent(props.document.id, body, mime.value);
}
</script>

<template>
  <div class="text-sm">
    <div v-if="model" class="flex flex-col gap-2 p-2">
      <div class="flex items-center gap-2">
        <span class="font-semibold flex-1 truncate">{{ document?.title || $t('kindViews.sheet.label') }}</span>
        <span class="opacity-50 text-xs tabular-nums">{{ dims.rows }}×{{ dims.cols }}</span>
        <VButton variant="ghost" @click="editOpen = true">{{ $t('kindViews.sheet.edit') }}</VButton>
      </div>
      <div class="preview-wrap">
        <table class="sheet-preview">
          <thead>
            <tr>
              <th class="corner" />
              <th v-for="c in previewCols" :key="c">{{ c }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in previewRowCount" :key="r">
              <td class="rownum">{{ r }}</td>
              <td v-for="c in previewCols" :key="c + r">{{ display(c, r) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    <div v-else class="opacity-50 p-2">{{ $t('kindViews.sheet.empty') }}</div>

    <VModal v-model="editOpen" :title="$t('kindViews.sheet.label')">
      <div class="edit-host">
        <SheetView
          v-if="editOpen && model"
          :doc="model"
          :project-id="projectId"
          :doc-path="path"
          @update:doc="onEdit"
        />
      </div>
    </VModal>
  </div>
</template>

<style scoped>
.preview-wrap {
  overflow: auto;
  max-height: 14rem;
  border: 1px solid color-mix(in oklab, var(--color-base-content) 15%, transparent);
  border-radius: 0.4rem;
}
.sheet-preview {
  border-collapse: collapse;
  font-size: 0.78rem;
  width: 100%;
}
.sheet-preview th,
.sheet-preview td {
  border: 1px solid color-mix(in oklab, var(--color-base-content) 10%, transparent);
  padding: 0.15rem 0.4rem;
  text-align: left;
  white-space: nowrap;
  max-width: 12rem;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sheet-preview th {
  background: var(--color-base-200);
  font-family: ui-monospace, monospace;
  font-weight: 600;
  color: color-mix(in oklab, var(--color-base-content) 70%, transparent);
  text-align: center;
}
.sheet-preview .rownum,
.sheet-preview .corner {
  background: var(--color-base-200);
  color: color-mix(in oklab, var(--color-base-content) 55%, transparent);
  font-family: ui-monospace, monospace;
  text-align: center;
}
.edit-host {
  height: 70vh;
}
</style>

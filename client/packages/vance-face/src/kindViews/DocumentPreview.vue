<script setup lang="ts">
import { computed, defineAsyncComponent } from 'vue';
import { documentContentUrl } from '@vance/shared';

// Office previews — async so the mammoth / xlsx bundles stay out
// of the initial document-app chunk; they only load when the user
// opens a DOCX/XLSX document.
const DocxView = defineAsyncComponent(() => import('./DocxView.vue'));
const XlsxView = defineAsyncComponent(() => import('./XlsxView.vue'));

const DOCX_MIME = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

const props = defineProps<{
  documentId: string;
  mimeType?: string | null;
  /** True when the source is editable inline-text — preview steps
   *  out of the way for those (the editor handles them). */
  inline?: boolean;
}>();

const streamUrl = computed(() =>
  props.documentId ? documentContentUrl(props.documentId, false) : '',
);

const kind = computed<'image' | 'pdf' | 'docx' | 'xlsx' | 'inline' | 'binary'>(() => {
  if (props.inline) return 'inline';
  const mt = (props.mimeType ?? '').toLowerCase();
  if (mt === 'application/pdf') return 'pdf';
  if (mt === DOCX_MIME) return 'docx';
  if (mt === XLSX_MIME) return 'xlsx';
  if (mt.startsWith('image/')) return 'image';
  return 'binary';
});

const downloadUrl = computed(() =>
  props.documentId ? documentContentUrl(props.documentId, true) : '',
);

defineExpose({ downloadUrl });
</script>

<template>
  <div class="document-preview">
    <!-- Inline-text — handled by the parent's editor; we render nothing. -->
    <div v-if="kind === 'inline'" />

    <!-- Image: direct <img>, capped via CSS. -->
    <div v-else-if="kind === 'image'" class="flex justify-center">
      <img
        :src="streamUrl"
        :alt="mimeType ?? 'image'"
        class="document-preview__image"
        loading="lazy"
      />
    </div>

    <!-- PDF: native browser viewer via <iframe>. Same-origin request
         carries the vance_access cookie, so the brain content endpoint
         streams the bytes; the browser's built-in PDF plugin handles
         pagination, zoom, search, print. -->
    <iframe
      v-else-if="kind === 'pdf'"
      :src="streamUrl"
      :title="mimeType ?? 'PDF'"
      class="document-preview__pdf"
    />

    <!-- DOCX: mammoth.js client-side preview, read-only. -->
    <DocxView
      v-else-if="kind === 'docx'"
      mode="editor"
      :document-id="documentId"
    />

    <!-- XLSX: SheetJS client-side preview, read-only with sheet tabs. -->
    <XlsxView
      v-else-if="kind === 'xlsx'"
      mode="editor"
      :document-id="documentId"
    />

    <!-- Binary: nothing useful to render in-page. The parent shows
         a Download button. -->
    <div v-else class="text-sm opacity-70 italic">
      {{ $t('documents.preview.binary') }}
    </div>
  </div>
</template>

<style scoped>
.document-preview__image {
  max-width: 100%;
  max-height: 70vh;
  object-fit: contain;
  border-radius: 0.5rem;
}

.document-preview__pdf {
  display: block;
  width: 100%;
  min-height: 80vh;
  border: 0;
  border-radius: 0.5rem;
  background: #fff;
}
</style>

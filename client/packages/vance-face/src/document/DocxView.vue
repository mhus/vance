<script setup lang="ts">
/**
 * Read-only preview for `kind: docx` documents. Loads the DOCX
 * bytes via the documents content endpoint, runs them through
 * mammoth.js client-side to produce HTML, sanitises with
 * DOMPurify, and mounts the result.
 *
 * Editing is not in scope here — that lives on top of an
 * ONLYOFFICE / Collabora integration (see
 * planning/web-office-suite.md, Layer C). This component covers
 * "ich will mal kurz reingucken" without any office-server setup.
 *
 * Layout-Treue ist niedrig (kein Tabs/Margins/Numbering), Lese-
 * Treue exzellent — Headings, Bold/Italic, Lists, Tables,
 * Hyperlinks, embedded Bilder. Bei Komment-Threads, Footnotes,
 * Track-Changes verlässt der User den Preview und nutzt
 * Word/Pages/LibreOffice lokal.
 */
import { computed, onMounted, ref, watch } from 'vue';
import { documentContentUrl } from '@vance/shared';
import DOMPurify from 'dompurify';
import mammoth from 'mammoth';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';

interface Props {
  mode?: 'editor' | 'embedded';
  /** Embedded channel (chat fence → vance:-link → loaded doc). */
  document?: DocumentDto;
  embedRef?: EmbedRef;
  /** Editor channel (document-app preview pane) — caller passes
   *  the id directly when there's no full DTO to hand in. */
  documentId?: string;
}

const props = withDefaults(defineProps<Props>(), { mode: 'embedded' });

const loading = ref<boolean>(false);
const loadError = ref<string | null>(null);
const html = ref<string>('');
const warnings = ref<string[]>([]);

const url = computed<string>(() => {
  const id = props.document?.id ?? props.documentId;
  if (!id) return '';
  return documentContentUrl(id);
});

async function loadDocx(): Promise<void> {
  if (!url.value) return;
  loading.value = true;
  loadError.value = null;
  html.value = '';
  warnings.value = [];
  try {
    // cache: 'no-store' is critical — after an office-edit save
    // the bytes change but the URL stays identical, and the
    // browser would otherwise hand us the stale prior body.
    const res = await fetch(url.value, {
      credentials: 'include',
      cache: 'no-store',
    });
    if (!res.ok) {
      throw new Error(`HTTP ${res.status} ${res.statusText}`);
    }
    const arrayBuffer = await res.arrayBuffer();
    const result = await mammoth.convertToHtml({ arrayBuffer });
    // mammoth's HTML is structured but unstyled; DOMPurify strips
    // anything it doesn't recognise (event handlers, scripts) —
    // mammoth output is already safe but we sanitise defensively.
    html.value = DOMPurify.sanitize(result.value, {
      USE_PROFILES: { html: true },
    });
    if (result.messages && result.messages.length > 0) {
      warnings.value = result.messages
        .map((m) => m.message)
        .slice(0, 5);
    }
  } catch (e) {
    loadError.value = (e as Error).message || 'Failed to load DOCX';
  } finally {
    loading.value = false;
  }
}

onMounted(() => { void loadDocx(); });
watch(() => url.value, () => { void loadDocx(); });
</script>

<template>
  <div :class="['docx-view', `docx-view--${mode}`]">
    <div v-if="loading" class="docx-state">Lädt DOCX…</div>
    <div v-else-if="loadError" class="docx-state docx-state--err">
      <strong>Konnte DOCX nicht lesen:</strong> {{ loadError }}
    </div>
    <template v-else>
      <div class="docx-toolbar">
        <button
          type="button"
          class="docx-reload"
          :title="'Vorschau aktualisieren — zeigt Änderungen nach einem Office-Edit-Save.'"
          @click="loadDocx"
        >🔄 Aktualisieren</button>
        <div
          v-if="warnings.length"
          class="docx-warnings"
          :title="warnings.join('\n')"
        >
          {{ warnings.length }} Konvertierungs-Hinweis(e) — Layout
          kann von Word abweichen.
        </div>
      </div>
      <!-- mammoth's HTML is already DOMPurify-sanitised; v-html
           is appropriate here. -->
      <div class="docx-stage" v-html="html"></div>
    </template>
  </div>
</template>

<style scoped>
.docx-view {
  background: hsl(var(--b1));
  border: 1px solid hsl(var(--bc) / 0.15);
  border-radius: 0.5rem;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.docx-view--editor { min-height: 420px; height: 65vh; }
.docx-view--embedded { min-height: 16rem; height: 22rem; }

.docx-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.875rem;
  opacity: 0.7;
  padding: 1rem;
}
.docx-state--err { opacity: 1; color: hsl(var(--er)); }

.docx-toolbar {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.3rem 0.5rem;
  border-bottom: 1px solid hsl(var(--bc) / 0.1);
  background: hsl(var(--b2) / 0.4);
  flex-shrink: 0;
}
.docx-reload {
  font-size: 0.78rem;
  padding: 0.2rem 0.6rem;
  border: 1px solid hsl(var(--bc) / 0.2);
  border-radius: 0.25rem;
  background: transparent;
  cursor: pointer;
  color: inherit;
}
.docx-reload:hover {
  background: hsl(var(--bc) / 0.08);
}
.docx-warnings {
  font-size: 0.75rem;
  background: hsl(var(--wa) / 0.15);
  color: hsl(var(--bc) / 0.7);
  padding: 0.3rem 0.6rem;
  border-radius: 0.25rem;
  cursor: help;
  flex: 1;
}

.docx-stage {
  flex: 1;
  overflow: auto;
  padding: 1.5rem 2rem;
  background: white;
  color: #111;
  font-family: 'Times New Roman', Times, serif;
  font-size: 11pt;
  line-height: 1.45;
}

/* mammoth output: headings, paragraphs, lists, tables, code,
   images — minimal print-like reset so the preview reads close
   to Word's defaults without trying to be pixel-perfect. */
.docx-stage :deep(h1) { font-size: 18pt; margin: 1em 0 0.5em; font-family: Helvetica, Arial, sans-serif; }
.docx-stage :deep(h2) { font-size: 14pt; margin: 1em 0 0.4em; font-family: Helvetica, Arial, sans-serif; }
.docx-stage :deep(h3) { font-size: 12pt; margin: 0.8em 0 0.3em; font-family: Helvetica, Arial, sans-serif; }
.docx-stage :deep(h4),
.docx-stage :deep(h5),
.docx-stage :deep(h6) { font-size: 11pt; margin: 0.6em 0 0.3em; font-family: Helvetica, Arial, sans-serif; font-weight: 600; }
.docx-stage :deep(p) { margin: 0.4em 0 0.7em; text-align: justify; }
.docx-stage :deep(ul),
.docx-stage :deep(ol) { margin: 0.4em 0 0.7em 1.6em; padding: 0; }
.docx-stage :deep(li) { margin: 0.1em 0; }
.docx-stage :deep(table) { border-collapse: collapse; margin: 0.6em 0; font-size: 10pt; }
.docx-stage :deep(th),
.docx-stage :deep(td) { border: 1px solid #aaa; padding: 0.25em 0.5em; vertical-align: top; }
.docx-stage :deep(th) { background: #efefef; font-weight: bold; }
.docx-stage :deep(img) { max-width: 100%; height: auto; }
.docx-stage :deep(a) { color: #1a4a8a; text-decoration: none; }
.docx-stage :deep(blockquote) {
  border-left: 3px solid #ccc;
  margin: 0.5em 0;
  padding: 0.1em 0.9em;
  color: #444;
  font-style: italic;
}
</style>

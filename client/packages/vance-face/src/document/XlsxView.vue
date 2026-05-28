<script setup lang="ts">
/**
 * Read-only preview for `kind: xlsx` documents. Loads the XLSX
 * bytes via the documents content endpoint, parses with SheetJS
 * client-side, renders each sheet as an HTML table.
 *
 * Editing is not in scope here — see
 * planning/web-office-suite.md, Layer C (ONLYOFFICE / Collabora).
 *
 * Scope: cells with text and formula-resultate, merged cells via
 * sheet_to_html, multi-sheet tabs. Charts, Pivot-Tables, complex
 * conditional formatting werden im Preview weggelassen — der User
 * sieht die Zellen-Werte, mehr nicht. Für vollständige Bearbeitung:
 * Download + lokal in Excel/LibreOffice/Numbers öffnen.
 */
import { computed, onMounted, ref, watch } from 'vue';
import { documentContentUrl } from '@vance/shared';
import DOMPurify from 'dompurify';
import * as XLSX from 'xlsx';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';

interface Props {
  mode?: 'editor' | 'embedded';
  document?: DocumentDto;
  embedRef?: EmbedRef;
  /** Editor channel — caller passes the id when there's no DTO. */
  documentId?: string;
}

const props = withDefaults(defineProps<Props>(), { mode: 'embedded' });

const loading = ref<boolean>(false);
const loadError = ref<string | null>(null);
const sheetNames = ref<string[]>([]);
const activeSheet = ref<string>('');
const sheetHtml = ref<Record<string, string>>({});

const url = computed<string>(() => {
  const id = props.document?.id ?? props.documentId;
  if (!id) return '';
  return documentContentUrl(id);
});

const currentHtml = computed<string>(
  () => sheetHtml.value[activeSheet.value] ?? '',
);

async function loadXlsx(): Promise<void> {
  if (!url.value) return;
  loading.value = true;
  loadError.value = null;
  sheetNames.value = [];
  activeSheet.value = '';
  sheetHtml.value = {};
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
    const wb = XLSX.read(arrayBuffer, { type: 'array' });
    sheetNames.value = [...wb.SheetNames];
    activeSheet.value = sheetNames.value[0] ?? '';
    const rendered: Record<string, string> = {};
    for (const name of wb.SheetNames) {
      const ws = wb.Sheets[name];
      if (!ws) continue;
      const html = XLSX.utils.sheet_to_html(ws, { editable: false });
      rendered[name] = DOMPurify.sanitize(html, {
        USE_PROFILES: { html: true },
      });
    }
    sheetHtml.value = rendered;
  } catch (e) {
    loadError.value = (e as Error).message || 'Failed to load XLSX';
  } finally {
    loading.value = false;
  }
}

function selectSheet(name: string): void {
  activeSheet.value = name;
}

onMounted(() => { void loadXlsx(); });
watch(() => url.value, () => { void loadXlsx(); });
</script>

<template>
  <div :class="['xlsx-view', `xlsx-view--${mode}`]">
    <div v-if="loading" class="xlsx-state">Lädt XLSX…</div>
    <div v-else-if="loadError" class="xlsx-state xlsx-state--err">
      <strong>Konnte XLSX nicht lesen:</strong> {{ loadError }}
    </div>
    <template v-else>
      <div class="xlsx-toolbar">
        <button
          type="button"
          class="xlsx-reload"
          title="Vorschau aktualisieren — zeigt Änderungen nach einem Office-Edit-Save."
          @click="loadXlsx"
        >🔄 Aktualisieren</button>
        <div v-if="sheetNames.length > 1" class="xlsx-tabs">
          <button
            v-for="name in sheetNames"
            :key="name"
            type="button"
            :class="['xlsx-tab', { 'xlsx-tab--active': name === activeSheet }]"
            @click="selectSheet(name)"
          >{{ name }}</button>
        </div>
      </div>
      <!-- SheetJS HTML is already DOMPurify-sanitised; v-html is OK. -->
      <div class="xlsx-stage" v-html="currentHtml"></div>
    </template>
  </div>
</template>

<style scoped>
.xlsx-view {
  background: hsl(var(--b1));
  border: 1px solid hsl(var(--bc) / 0.15);
  border-radius: 0.5rem;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.xlsx-view--editor { min-height: 420px; height: 65vh; }
.xlsx-view--embedded { min-height: 16rem; height: 22rem; }

.xlsx-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.875rem;
  opacity: 0.7;
  padding: 1rem;
}
.xlsx-state--err { opacity: 1; color: hsl(var(--er)); }

.xlsx-toolbar {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.3rem 0.5rem;
  border-bottom: 1px solid hsl(var(--bc) / 0.15);
  background: hsl(var(--b2));
  flex-shrink: 0;
}
.xlsx-reload {
  font-size: 0.78rem;
  padding: 0.2rem 0.6rem;
  border: 1px solid hsl(var(--bc) / 0.2);
  border-radius: 0.25rem;
  background: transparent;
  cursor: pointer;
  color: inherit;
}
.xlsx-reload:hover {
  background: hsl(var(--bc) / 0.08);
}
.xlsx-tabs {
  display: flex;
  gap: 0.25rem;
  overflow-x: auto;
  flex: 1;
}
.xlsx-tab {
  flex: 0 0 auto;
  font-size: 0.78rem;
  padding: 0.2rem 0.6rem;
  border: 1px solid hsl(var(--bc) / 0.2);
  border-radius: 0.25rem;
  background: transparent;
  cursor: pointer;
  color: inherit;
}
.xlsx-tab:hover { background: hsl(var(--bc) / 0.08); }
.xlsx-tab--active {
  background: hsl(var(--p) / 0.18);
  border-color: hsl(var(--p) / 0.6);
}

.xlsx-stage {
  flex: 1;
  overflow: auto;
  background: white;
  color: #111;
  font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif;
  font-size: 10pt;
}

/* SheetJS' sheet_to_html: <table>, <tr>, <td>, no headers as <th>. */
.xlsx-stage :deep(table) {
  border-collapse: collapse;
  margin: 0;
}
.xlsx-stage :deep(td) {
  border: 1px solid #d0d0d0;
  padding: 0.2em 0.5em;
  vertical-align: top;
  white-space: pre-wrap;
  min-width: 4em;
}
.xlsx-stage :deep(tr:first-child td) {
  background: #f3f3f3;
  font-weight: 600;
}
</style>

<script setup lang="ts">
/**
 * Mount the ONLYOFFICE / Collabora editor for a Vance document.
 *
 * Flow:
 *   1. Fetch the editor config from the brain
 *      ({@code GET /brain/{tenant}/office/session/{docId}}).
 *      The brain returns document metadata, signed URLs for
 *      download/callback, and a JWT-signed config the doc-server
 *      requires.
 *   2. Inject the doc-server's JS SDK
 *      ({@code <office.url>/web-apps/apps/api/documents/api.js})
 *      once per page lifetime.
 *   3. Instantiate {@code DocsAPI.DocEditor(targetId, config)} in
 *      a freshly-created container.
 *   4. Auto-save: the doc-server posts back to
 *      {@code /brain/{tenant}/office/callback/{docId}} on each save.
 *      We don't watch save-events client-side — the round-trip is
 *      already server-bound.
 *
 * Spec: planning/web-office-suite.md §4
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { brainFetch } from '@vance/shared';

interface Props {
  documentId: string;
  /** Mime type of the document — drives the {@code fileType}
   *  parameter the editor uses to pick the right view. */
  mimeType?: string | null;
}

const props = defineProps<Props>();

const containerRef = ref<HTMLElement | null>(null);
const loading = ref(false);
const loadError = ref<string | null>(null);
const officeNotConfigured = ref(false);

// Generated per-instance — ONLYOFFICE's SDK targets DocEditor by
// element id. Random suffix avoids clashes if two editors mount
// in the same session.
const editorElementId = `vance-office-${Math.random().toString(36).slice(2, 10)}`;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
let editorInstance: any = null;
let sdkLoadPromise: Promise<void> | null = null;

interface OfficeSessionResponse {
  document: {
    fileType: string;
    key: string;
    title: string;
    url: string;
  };
  documentType: string;
  editorConfig: {
    callbackUrl: string;
    mode: string;
  };
  token: string;
  officeUrl: string;
  provider: string;
}

const isLoaded = computed(() => !loading.value && !loadError.value && !officeNotConfigured.value);

async function loadEditor(): Promise<void> {
  if (!props.documentId) return;
  loading.value = true;
  loadError.value = null;
  officeNotConfigured.value = false;
  destroyEditor();

  let session: OfficeSessionResponse;
  try {
    session = await brainFetch<OfficeSessionResponse>(
      'GET',
      `office/session/${encodeURIComponent(props.documentId)}`,
    );
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    // The brain returns 503 with {error:'office-not-configured'}
    // when the office.* settings aren't filled in — we want a
    // helpful empty-state, not a red error banner.
    if (/503/.test(msg) || /office-not-configured/.test(msg)) {
      officeNotConfigured.value = true;
    } else {
      loadError.value = msg;
    }
    loading.value = false;
    return;
  }

  try {
    await loadSdk(session.officeUrl);
  } catch (e) {
    loadError.value = `Konnte SDK von ${session.officeUrl} nicht laden: `
      + (e instanceof Error ? e.message : String(e));
    loading.value = false;
    return;
  }

  try {
    instantiateEditor(session);
  } catch (e) {
    loadError.value = `Editor-Mount fehlgeschlagen: `
      + (e instanceof Error ? e.message : String(e));
    loading.value = false;
    return;
  }

  loading.value = false;
}

/**
 * Inject the doc-server's API script once per page. Subsequent
 * mounts reuse the already-loaded {@code window.DocsAPI}.
 */
function loadSdk(officeUrl: string): Promise<void> {
  if (typeof window !== 'undefined'
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      && (window as any).DocsAPI) {
    return Promise.resolve();
  }
  if (sdkLoadPromise) return sdkLoadPromise;

  sdkLoadPromise = new Promise((resolve, reject) => {
    const base = officeUrl.replace(/\/+$/, '');
    const src = `${base}/web-apps/apps/api/documents/api.js`;
    const script = document.createElement('script');
    script.src = src;
    script.async = true;
    script.onload = () => resolve();
    script.onerror = () => {
      sdkLoadPromise = null;
      reject(new Error(`failed to load ${src}`));
    };
    document.head.appendChild(script);
  });
  return sdkLoadPromise;
}

function instantiateEditor(session: OfficeSessionResponse): void {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const DocsAPI = (window as any).DocsAPI;
  if (!DocsAPI) {
    throw new Error('DocsAPI not present after SDK load');
  }
  // The container must exist in the DOM before DocEditor mounts
  // and must be empty — DocsAPI replaces its innerHTML.
  if (!containerRef.value) {
    throw new Error('container ref not ready');
  }
  containerRef.value.innerHTML = '';
  const target = document.createElement('div');
  target.id = editorElementId;
  target.style.width = '100%';
  target.style.height = '100%';
  containerRef.value.appendChild(target);

  const config = {
    document: session.document,
    documentType: session.documentType,
    editorConfig: {
      ...session.editorConfig,
      // Reasonable defaults; the operator can override per-tenant
      // via further setting fields later.
      lang: navigator.language?.split('-')[0] ?? 'de',
      customization: {
        compactHeader: false,
        autosave: true,
      },
    },
    token: session.token,
    width: '100%',
    height: '100%',
    type: 'desktop',
  };

  editorInstance = new DocsAPI.DocEditor(editorElementId, config);
}

function destroyEditor(): void {
  try {
    editorInstance?.destroyEditor?.();
  } catch {
    /* best-effort cleanup; the SDK occasionally throws on double-destroy */
  }
  editorInstance = null;
  if (containerRef.value) containerRef.value.innerHTML = '';
}

onMounted(() => { void loadEditor(); });
watch(() => props.documentId, () => { void loadEditor(); });
onBeforeUnmount(() => { destroyEditor(); });
</script>

<template>
  <div class="office-editor">
    <div v-if="loading" class="office-state">
      Office-Editor wird geladen…
    </div>
    <div v-else-if="officeNotConfigured" class="office-state office-state--info">
      <strong>Office-Editor ist für dieses Projekt nicht konfiguriert.</strong>
      <p>
        Ein Administrator kann unter <em>Einstellungen → Integrations →
        Office-Editor (ONLYOFFICE / Collabora)</em> die Anbindung zu
        einer self-hosted Document-Server-Instanz einrichten.
      </p>
    </div>
    <div v-else-if="loadError" class="office-state office-state--err">
      <strong>Editor konnte nicht geladen werden:</strong>
      <pre class="office-error-msg">{{ loadError }}</pre>
    </div>
    <div
      v-show="isLoaded"
      ref="containerRef"
      class="office-frame"
    ></div>
  </div>
</template>

<style scoped>
.office-editor {
  display: flex;
  flex-direction: column;
  height: 65vh;
  min-height: 480px;
  background: hsl(var(--b1));
  border: 1px solid hsl(var(--bc) / 0.15);
  border-radius: 0.5rem;
  overflow: hidden;
}
.office-frame {
  flex: 1;
  min-height: 0;
  width: 100%;
}
.office-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  text-align: center;
  padding: 2rem;
  gap: 0.5rem;
  font-size: 0.9rem;
}
.office-state--err {
  color: hsl(var(--er));
}
.office-state--info p {
  max-width: 32rem;
  opacity: 0.75;
  margin: 0;
}
.office-error-msg {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, monospace;
  font-size: 0.8125rem;
  background: hsl(var(--bc) / 0.06);
  padding: 0.6rem 0.8rem;
  border-radius: 0.25rem;
  white-space: pre-wrap;
  max-width: 40rem;
  text-align: left;
}
</style>

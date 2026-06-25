import { ref, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type { CortexDocument } from '../types';
import { useCortexStore } from '../stores/cortexStore';
import type { RunAdapter, RunHandle, RunInput, RunState } from './types';

const TEX_EXTS = ['.tex', '.ltx', '.latex'];
const COMPOSE_NAMES = ['tex-compose.yaml', 'tex-compose.yml'];

function isTexDocument(doc: CortexDocument): boolean {
  const p = doc.path.toLowerCase();
  if (TEX_EXTS.some((ext) => p.endsWith(ext))) return true;
  return COMPOSE_NAMES.some((name) => p.endsWith(name));
}

function isComposeFile(doc: CortexDocument): boolean {
  const p = doc.path.toLowerCase();
  return COMPOSE_NAMES.some((name) => p.endsWith(name));
}

/**
 * For a {@code .tex} file, find a sibling {@code tex-compose.yaml} in
 * the same directory via the store's file list. Returns the compose
 * path or {@code null} when none is found.
 */
function findSiblingCompose(
  doc: CortexDocument,
  files: CortexDocument[],
): string | null {
  const dir = doc.path.includes('/')
    ? doc.path.substring(0, doc.path.lastIndexOf('/'))
    : '';
  for (const f of files) {
    const fLower = f.path.toLowerCase();
    if (COMPOSE_NAMES.some((name) => fLower.endsWith(name))) {
      const fDir = f.path.includes('/')
        ? f.path.substring(0, f.path.lastIndexOf('/'))
        : '';
      if (fDir === dir) return f.path;
    }
  }
  return null;
}

interface TexCompileResponse {
  success: boolean;
  pdfPath?: string;
  error?: string;
  logExcerpt?: string;
  elapsedMs: number;
}

/**
 * TeX compilation adapter: posts the compose path to the brain's
 * {@code tex/compile} endpoint, which runs latexmk (or the configured
 * rbehzadan executor) server-side and imports the resulting PDF as a
 * document. The call is synchronous — the backend blocks until the
 * compiler finishes (or times out).
 *
 * <p>Matches both {@code .tex} files and {@code tex-compose.yaml}
 * files. For {@code .tex} files, the adapter looks up a sibling
 * {@code tex-compose.yaml} in the same directory via the cortex store;
 * when none is found, the run fails with a hint to create one.
 *
 * <p>The result carries {@code { pdfPath }} on success so the shell
 * can render an "Open PDF" button that opens the freshly imported PDF
 * as a new tab.
 */
export const texRunner: RunAdapter = {
  id: 'tex',
  label: 'Generate PDF',
  matches: isTexDocument,
  async execute({ doc, projectId }: RunInput): Promise<RunHandle> {
    const state: Ref<RunState> = ref('starting');
    const logLines: Ref<string[]> = ref([]);
    const result: Ref<unknown> = ref(null);
    const error: Ref<string | null> = ref(null);
    const durationMs: Ref<number | null> = ref(null);

    let detached = false;

    // Resolve the compose path. For a tex-compose.yaml file, use it
    // directly. For a .tex file, look for a sibling compose file.
    let composePath: string;
    if (isComposeFile(doc)) {
      composePath = doc.path;
    } else {
      const store = useCortexStore();
      composePath = findSiblingCompose(doc, store.files) ?? '';
      if (!composePath) {
        state.value = 'failed';
        error.value =
          'No tex-compose.yaml found in the same directory. '
          + 'Create one that lists this .tex file as "main".';
        return makeHandle();
      }
      logLines.value.push(
        `[tex] Using compose file: ${composePath}`,
      );
    }

    state.value = 'running';

    try {
      const resp = await brainFetch<TexCompileResponse>(
        'POST',
        'tex/compile',
        {
          body: {
            composePath,
            projectId,
          },
        },
      );

      if (detached) return makeHandle();

      durationMs.value = resp.elapsedMs ?? null;

      if (resp.success) {
        state.value = 'finished';
        result.value = { pdfPath: resp.pdfPath ?? null };
        logLines.value.push(
          `[tex] PDF generated: ${resp.pdfPath ?? '(unknown path)'}`,
          `[tex] Elapsed: ${resp.elapsedMs ?? '?'} ms`,
        );
      } else {
        state.value = 'failed';
        error.value = resp.error ?? 'Compilation failed';
        if (resp.logExcerpt) {
          for (const line of resp.logExcerpt.split('\n')) {
            if (line.length > 0) logLines.value.push(line);
          }
        }
      }
    } catch (e) {
      if (detached) return makeHandle();
      state.value = 'failed';
      error.value = e instanceof Error ? e.message : 'Compile request failed';
    }

    function makeHandle(): RunHandle {
      return {
        get id(): string { return `tex-${doc.id}`; },
        state,
        logLines,
        result,
        error,
        durationMs,
        async cancel(): Promise<void> {
          // Backend compile is synchronous — no cancellation endpoint.
          // Just transition to cancelled locally.
          state.value = 'cancelled';
        },
        detach(): void {
          detached = true;
        },
      };
    }

    return makeHandle();
  },
};

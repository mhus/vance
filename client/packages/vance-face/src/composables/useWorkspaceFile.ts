import { ref, type Ref } from 'vue';
import { brainBaseUrl, getTenantId } from '@vance/shared';

/**
 * One-shot fetcher for a single workspace file. The Brain serves raw
 * bytes; we treat known text-ish extensions as text and surface them
 * to a `<CodeEditor>`/`<MarkdownView>`. Binary content stays a URL —
 * the caller's `<img>` (cookie auth attaches automatically on
 * same-origin) or `<a download>` consumes it.
 */

export type RenderMode = 'markdown' | 'text' | 'image' | 'binary';

export interface FileLoadResult {
  /** Resolved render mode based on filename extension. */
  mode: RenderMode;
  /** Text payload — populated only when {@link mode} is `'text'` or `'markdown'`. */
  text: string | null;
  /** Stable URL for `<img src>` / `<a href download>` use. */
  url: string;
  /** MIME-type hint for `<CodeEditor>` syntax highlighting. */
  mimeType: string;
}

interface UseWorkspaceFile {
  result: Ref<FileLoadResult | null>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: (projectId: string, path: string, name: string) => Promise<void>;
  clear: () => void;
}

const TEXT_EXTS = new Set([
  'txt', 'log', 'md', 'markdown', 'json', 'yaml', 'yml', 'xml', 'html', 'htm',
  'css', 'js', 'mjs', 'cjs', 'ts', 'tsx', 'jsx', 'py', 'sh', 'bash', 'zsh',
  'java', 'kt', 'rs', 'go', 'rb', 'sql', 'conf', 'cfg', 'ini', 'properties',
  'env', 'toml', 'csv', 'tsv', 'gitignore', 'editorconfig',
]);

const IMAGE_EXTS = new Set(['png', 'jpg', 'jpeg', 'gif', 'svg', 'webp', 'bmp', 'avif']);

const MIME_BY_EXT: Record<string, string> = {
  md: 'text/markdown', markdown: 'text/markdown',
  json: 'application/json',
  yaml: 'application/yaml', yml: 'application/yaml',
  xml: 'application/xml',
  html: 'text/html', htm: 'text/html',
  css: 'text/css',
  js: 'application/javascript', mjs: 'application/javascript', cjs: 'application/javascript',
  ts: 'application/typescript', tsx: 'application/typescript', jsx: 'application/javascript',
  py: 'text/x-python',
  sh: 'application/x-sh', bash: 'application/x-sh', zsh: 'application/x-sh',
  java: 'text/x-java-source',
  sql: 'application/sql',
  toml: 'application/toml',
  txt: 'text/plain', log: 'text/plain', conf: 'text/plain', cfg: 'text/plain',
  ini: 'text/plain', properties: 'text/plain', env: 'text/plain',
  csv: 'text/csv', tsv: 'text/tab-separated-values',
};

function ext(name: string): string {
  const i = name.lastIndexOf('.');
  return i < 0 ? '' : name.slice(i + 1).toLowerCase();
}

function pickMode(name: string): RenderMode {
  const e = ext(name);
  if (e === 'md' || e === 'markdown') return 'markdown';
  if (TEXT_EXTS.has(e)) return 'text';
  if (IMAGE_EXTS.has(e)) return 'image';
  return 'binary';
}

function pickMime(name: string): string {
  return MIME_BY_EXT[ext(name)] ?? 'text/plain';
}

export function workspaceFileUrl(projectId: string, path: string): string {
  const tenant = getTenantId();
  if (!tenant) return '';
  const params = new URLSearchParams({ path });
  return `${brainBaseUrl()}/brain/${encodeURIComponent(tenant)}/projects/${encodeURIComponent(projectId)}/workspace/file?${params}`;
}

export function useWorkspaceFile(): UseWorkspaceFile {
  const result = ref<FileLoadResult | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function load(projectId: string, path: string, name: string): Promise<void> {
    loading.value = true;
    error.value = null;
    const mode = pickMode(name);
    const mimeType = pickMime(name);
    const url = workspaceFileUrl(projectId, path);
    try {
      let text: string | null = null;
      if (mode === 'text' || mode === 'markdown') {
        const r = await fetch(url, { credentials: 'include' });
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        text = await r.text();
      }
      result.value = { mode, text, url, mimeType };
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load file.';
      result.value = null;
    } finally {
      loading.value = false;
    }
  }

  function clear(): void {
    result.value = null;
    error.value = null;
  }

  return { result, loading, error, load, clear };
}

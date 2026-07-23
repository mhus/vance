import { ref, type Ref } from 'vue';
import type {
  ChatMessageInsightsDto,
  MarvinNodeInsightsDto,
  MemoryInsightsDto,
  PrakRunInsightsDto,
  SessionClientToolsDto,
  SessionInsightsDto,
  ThinkProcessInsightsDto,
} from '@vance/generated';
import { brainFetch, brainFetchBlob } from '@vance/shared';

interface SessionFilter {
  projectId?: string | null;
  userId?: string | null;
  status?: string | null;
}

/** Sessions fetched per page — matches the server-side default limit. */
const SESSION_PAGE_SIZE = 200;

/**
 * Read-only access to the insights inspector endpoints. One composable
 * per concept (sessions / processes / chat / memory / marvin tree)
 * keeps the component glue thin and the loading flags scoped.
 *
 * Sessions are paginated: the endpoint returns at most {@link
 * SESSION_PAGE_SIZE} rows sorted newest-first, so a busy tenant never
 * ships its whole session set. `reload` fetches the first page; `loadMore`
 * appends the next. `hasMore` is a heuristic — a full page implies there
 * may be more (avoids a separate count query).
 */
export function useInsightsSessions(): {
  sessions: Ref<SessionInsightsDto[]>;
  loading: Ref<boolean>;
  loadingMore: Ref<boolean>;
  hasMore: Ref<boolean>;
  error: Ref<string | null>;
  reload: (filter: SessionFilter) => Promise<void>;
  loadMore: () => Promise<void>;
} {
  const sessions = ref<SessionInsightsDto[]>([]);
  const loading = ref(false);
  const loadingMore = ref(false);
  const hasMore = ref(false);
  const error = ref<string | null>(null);
  let currentFilter: SessionFilter = {};

  async function fetchPage(offset: number): Promise<SessionInsightsDto[]> {
    const params = new URLSearchParams();
    if (currentFilter.projectId) params.set('projectId', currentFilter.projectId);
    if (currentFilter.userId) params.set('userId', currentFilter.userId);
    if (currentFilter.status) params.set('status', currentFilter.status);
    params.set('offset', String(offset));
    params.set('limit', String(SESSION_PAGE_SIZE));
    return brainFetch<SessionInsightsDto[]>('GET', `admin/sessions?${params.toString()}`);
  }

  async function reload(filter: SessionFilter): Promise<void> {
    currentFilter = filter;
    loading.value = true;
    error.value = null;
    try {
      const page = await fetchPage(0);
      sessions.value = page;
      hasMore.value = page.length === SESSION_PAGE_SIZE;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load sessions.';
      sessions.value = [];
      hasMore.value = false;
    } finally {
      loading.value = false;
    }
  }

  async function loadMore(): Promise<void> {
    if (loadingMore.value || loading.value || !hasMore.value) return;
    loadingMore.value = true;
    error.value = null;
    try {
      const page = await fetchPage(sessions.value.length);
      sessions.value = [...sessions.value, ...page];
      hasMore.value = page.length === SESSION_PAGE_SIZE;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load more sessions.';
    } finally {
      loadingMore.value = false;
    }
  }

  return { sessions, loading, loadingMore, hasMore, error, reload, loadMore };
}

export function useSessionProcesses(): {
  processes: Ref<ThinkProcessInsightsDto[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: (sessionId: string) => Promise<void>;
  clear: () => void;
} {
  const processes = ref<ThinkProcessInsightsDto[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);

  function clear(): void { processes.value = []; error.value = null; }

  async function load(sessionId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      processes.value = await brainFetch<ThinkProcessInsightsDto[]>(
        'GET', `admin/sessions/${encodeURIComponent(sessionId)}/processes`);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load processes.';
      processes.value = [];
    } finally {
      loading.value = false;
    }
  }

  return { processes, loading, error, load, clear };
}

export function useProcessDetail(): {
  process: Ref<ThinkProcessInsightsDto | null>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: (processId: string) => Promise<void>;
  clear: () => void;
} {
  const process = ref<ThinkProcessInsightsDto | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);

  function clear(): void { process.value = null; error.value = null; }

  async function load(processId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      process.value = await brainFetch<ThinkProcessInsightsDto>(
        'GET', `admin/processes/${encodeURIComponent(processId)}`);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load process.';
      process.value = null;
    } finally {
      loading.value = false;
    }
  }

  return { process, loading, error, load, clear };
}

export function useProcessChat(): {
  messages: Ref<ChatMessageInsightsDto[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: (processId: string) => Promise<void>;
  clear: () => void;
} {
  const messages = ref<ChatMessageInsightsDto[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);

  function clear(): void { messages.value = []; error.value = null; }

  async function load(processId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      messages.value = await brainFetch<ChatMessageInsightsDto[]>(
        'GET', `admin/processes/${encodeURIComponent(processId)}/chat`);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load chat.';
      messages.value = [];
    } finally {
      loading.value = false;
    }
  }

  return { messages, loading, error, load, clear };
}

export function useProcessMemory(): {
  entries: Ref<MemoryInsightsDto[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: (processId: string) => Promise<void>;
  clear: () => void;
} {
  const entries = ref<MemoryInsightsDto[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);

  function clear(): void { entries.value = []; error.value = null; }

  async function load(processId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      entries.value = await brainFetch<MemoryInsightsDto[]>(
        'GET', `admin/processes/${encodeURIComponent(processId)}/memory`);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load memory.';
      entries.value = [];
    } finally {
      loading.value = false;
    }
  }

  return { entries, loading, error, load, clear };
}

export function useProcessPrakRuns(): {
  runs: Ref<PrakRunInsightsDto[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: (processId: string) => Promise<void>;
  clear: () => void;
} {
  const runs = ref<PrakRunInsightsDto[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);

  function clear(): void { runs.value = []; error.value = null; }

  async function load(processId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      runs.value = await brainFetch<PrakRunInsightsDto[]>(
        'GET', `admin/processes/${encodeURIComponent(processId)}/prak-runs`);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load Prak runs.';
      runs.value = [];
    } finally {
      loading.value = false;
    }
  }

  return { runs, loading, error, load, clear };
}

export function useSessionClientTools(): {
  data: Ref<SessionClientToolsDto | null>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: (sessionId: string) => Promise<void>;
  clear: () => void;
} {
  const data = ref<SessionClientToolsDto | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);

  function clear(): void { data.value = null; error.value = null; }

  async function load(sessionId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      data.value = await brainFetch<SessionClientToolsDto>(
        'GET', `admin/sessions/${encodeURIComponent(sessionId)}/insights/client-tools`);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load client tools.';
      data.value = null;
    } finally {
      loading.value = false;
    }
  }

  return { data, loading, error, load, clear };
}

export function useMarvinTree(): {
  nodes: Ref<MarvinNodeInsightsDto[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: (processId: string) => Promise<void>;
  clear: () => void;
} {
  const nodes = ref<MarvinNodeInsightsDto[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);

  function clear(): void { nodes.value = []; error.value = null; }

  async function load(processId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      nodes.value = await brainFetch<MarvinNodeInsightsDto[]>(
        'GET', `admin/processes/${encodeURIComponent(processId)}/marvin-tree`);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load Marvin tree.';
      nodes.value = [];
    } finally {
      loading.value = false;
    }
  }

  return { nodes, loading, error, load, clear };
}

/**
 * Download the session-scoped JSON-lines diagnostic bundle and trigger
 * a browser save dialog. The server picks the filename
 * (`session-{id}-{ts}.jsonl`) via `Content-Disposition`; the local
 * default name is only used as a defensive fallback.
 */
export async function downloadSessionExport(sessionId: string): Promise<void> {
  const { blob, filename } = await brainFetchBlob(
    `admin/sessions/${encodeURIComponent(sessionId)}/export.jsonl`);
  const url = URL.createObjectURL(blob);
  try {
    const a = document.createElement('a');
    a.href = url;
    a.download = filename ?? `session-${sessionId}.jsonl`;
    a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    a.remove();
  } finally {
    // Revoke on the next tick so Chrome has time to start the download
    // before we tear down the blob URL.
    setTimeout(() => URL.revokeObjectURL(url), 0);
  }
}

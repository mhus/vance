import { ref, type Ref } from 'vue';
import type {
  ChatMessageInsightsDto,
  MarvinNodeInsightsDto,
  MemoryInsightsDto,
  SessionClientToolsDto,
  SessionInsightsDto,
  ThinkProcessInsightsDto,
} from '@vance/generated';
import { brainFetch } from '@vance/shared';

interface SessionFilter {
  projectId?: string | null;
  userId?: string | null;
  status?: string | null;
}

/**
 * Read-only access to the insights inspector endpoints. One composable
 * per concept (sessions / processes / chat / memory / marvin tree)
 * keeps the component glue thin and the loading flags scoped.
 */
export function useInsightsSessions(): {
  sessions: Ref<SessionInsightsDto[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  reload: (filter: SessionFilter) => Promise<void>;
} {
  const sessions = ref<SessionInsightsDto[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function reload(filter: SessionFilter): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const params = new URLSearchParams();
      if (filter.projectId) params.set('projectId', filter.projectId);
      if (filter.userId) params.set('userId', filter.userId);
      if (filter.status) params.set('status', filter.status);
      const path = `admin/sessions${params.toString() ? '?' + params.toString() : ''}`;
      sessions.value = await brainFetch<SessionInsightsDto[]>('GET', path);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load sessions.';
      sessions.value = [];
    } finally {
      loading.value = false;
    }
  }

  return { sessions, loading, error, reload };
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

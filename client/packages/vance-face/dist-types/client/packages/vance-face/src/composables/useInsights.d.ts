import { type Ref } from 'vue';
import type { ChatMessageInsightsDto, MarvinNodeInsightsDto, MemoryInsightsDto, PrakRunInsightsDto, SessionClientToolsDto, SessionInsightsDto, ThinkProcessInsightsDto } from '@vance/generated';
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
export declare function useInsightsSessions(): {
    sessions: Ref<SessionInsightsDto[]>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    reload: (filter: SessionFilter) => Promise<void>;
};
export declare function useSessionProcesses(): {
    processes: Ref<ThinkProcessInsightsDto[]>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    load: (sessionId: string) => Promise<void>;
    clear: () => void;
};
export declare function useProcessDetail(): {
    process: Ref<ThinkProcessInsightsDto | null>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    load: (processId: string) => Promise<void>;
    clear: () => void;
};
export declare function useProcessChat(): {
    messages: Ref<ChatMessageInsightsDto[]>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    load: (processId: string) => Promise<void>;
    clear: () => void;
};
export declare function useProcessMemory(): {
    entries: Ref<MemoryInsightsDto[]>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    load: (processId: string) => Promise<void>;
    clear: () => void;
};
export declare function useProcessPrakRuns(): {
    runs: Ref<PrakRunInsightsDto[]>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    load: (processId: string) => Promise<void>;
    clear: () => void;
};
export declare function useSessionClientTools(): {
    data: Ref<SessionClientToolsDto | null>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    load: (sessionId: string) => Promise<void>;
    clear: () => void;
};
export declare function useMarvinTree(): {
    nodes: Ref<MarvinNodeInsightsDto[]>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    load: (processId: string) => Promise<void>;
    clear: () => void;
};
/**
 * Download the session-scoped JSON-lines diagnostic bundle and trigger
 * a browser save dialog. The server picks the filename
 * (`session-{id}-{ts}.jsonl`) via `Content-Disposition`; the local
 * default name is only used as a defensive fallback.
 */
export declare function downloadSessionExport(sessionId: string): Promise<void>;
export {};
//# sourceMappingURL=useInsights.d.ts.map
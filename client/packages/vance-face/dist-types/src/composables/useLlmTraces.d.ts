import { type ComputedRef, type Ref } from 'vue';
import type { LlmTraceDto } from '@vance/generated';
/**
 * Group of trace rows belonging to one LLM round-trip — drives the
 * collapsible blocks the UI renders. Header summary fields are derived
 * from the contained legs (the OUTPUT row carries tokens / elapsedMs).
 */
export interface LlmTraceTurn {
    turnId: string;
    legs: LlmTraceDto[];
    /** UTC timestamp of the earliest leg in the group (ISO string). */
    startedAt: string | null;
    modelAlias: string | null;
    tokensIn: number | null;
    tokensOut: number | null;
    elapsedMs: number | null;
    /** Number of tool-call legs in this round-trip. */
    toolCallCount: number;
}
/**
 * Reactive wrapper around the LLM-trace inspection endpoint. One
 * instance per Insights process-tab — paginates through the rows of
 * one process, exposes them grouped by {@code turnId}.
 *
 * <p>Group order: the API returns rows newest-first by {@code createdAt};
 * we preserve that order at the group level (the most recent round-trip
 * sits on top), but within each group legs run in their natural
 * {@code sequence} ascending order.
 */
export declare function useLlmTraces(pageSize?: number): {
    items: Ref<LlmTraceDto[]>;
    turns: ComputedRef<LlmTraceTurn[]>;
    page: Ref<number>;
    totalCount: Ref<number>;
    pageSize: Ref<number>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    loadPage: (processId: string, page: number) => Promise<void>;
    reset: () => void;
};
//# sourceMappingURL=useLlmTraces.d.ts.map
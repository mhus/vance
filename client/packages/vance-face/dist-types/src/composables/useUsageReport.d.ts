import { type Ref } from 'vue';
import type { UsageReportDto } from '@vance/generated';
/**
 * Reactive wrapper around the tenant-wide LLM usage / cost reports.
 *
 * <p>Hits {@code GET /brain/{tenant}/usage/summary|by-project|by-model}
 * with optional from/to/groupBy params. Three independent refs so the
 * UI can render time-series + top-project + top-model in parallel
 * without coupling fetches.
 *
 * <p>Each loader takes the params it needs and writes the response
 * into its own ref. Loading + error are merged across all three so a
 * single spinner / banner can drive the tab.
 */
export declare function useUsageReport(): {
    summary: Ref<UsageReportDto | null>;
    byProject: Ref<UsageReportDto | null>;
    byModel: Ref<UsageReportDto | null>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    loadAll: (params: UsageQuery) => Promise<void>;
    reset: () => void;
};
export interface UsageQuery {
    from?: string;
    to?: string;
    groupBy?: 'day' | 'week' | 'month';
    projectId?: string | null;
}
//# sourceMappingURL=useUsageReport.d.ts.map
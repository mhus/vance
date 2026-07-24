package de.mhus.vance.addon.brain.finance.report;

import org.jspecify.annotations.Nullable;

/**
 * Invocation scope handed to a {@link FinanceReportProcessor}. Pure-presentation
 * processors (table, series) ignore it; service-backed ones (e.g. the LLM
 * {@code assessment} processor) need the tenant/project scope to reach shared
 * services like {@code LightLlmService}.
 */
public record ReportContext(
        String tenantId,
        @Nullable String projectId,
        @Nullable String processId,
        @Nullable String userId) {}

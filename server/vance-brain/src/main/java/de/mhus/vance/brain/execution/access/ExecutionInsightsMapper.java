package de.mhus.vance.brain.execution.access;

import de.mhus.vance.api.execution.ExecutionInsightsDto;
import de.mhus.vance.brain.execution.ExecutionRegistryEntry;

/**
 * Maps the internal {@code ExecutionRegistryEntry} record to its
 * web-UI-facing DTO. Plain field copy — kept in a separate helper so
 * both Layer 1 (direct path) and Layer 2 (always direct) share a
 * single conversion site and stay in lockstep when fields change.
 */
final class ExecutionInsightsMapper {

    private ExecutionInsightsMapper() {}

    static ExecutionInsightsDto toDto(ExecutionRegistryEntry e) {
        return ExecutionInsightsDto.builder()
                .id(e.executionId())
                .owner(e.owner().label())
                .tenantId(e.tenantId())
                .projectId(e.projectId())
                .sessionId(e.sessionId())
                .processId(e.processId())
                .command(e.command())
                .dirName(e.dirName())
                .startedAt(e.startedAt())
                .lastOutputAt(e.lastOutputAt())
                .endedAt(e.endedAt())
                .status(e.status().name())
                .exitCode(e.exitCode())
                .build();
    }
}

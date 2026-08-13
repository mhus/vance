package de.mhus.vance.shared.thinkprocess;

import de.mhus.vance.api.thinkprocess.ProcessSummary;

/**
 * Document → DTO projection for think-process listings. Shared by the
 * WebSocket {@code process-list} handler (bound session) and the REST
 * session-process endpoint the web session picker uses, so both surfaces
 * ship the same row shape.
 */
public final class ThinkProcessSummaryMapper {

    private ThinkProcessSummaryMapper() {}

    public static ProcessSummary toSummary(ThinkProcessDocument doc) {
        return ProcessSummary.builder()
                .id(doc.getId())
                .name(doc.getName())
                .title(doc.getTitle())
                .thinkEngine(doc.getThinkEngine())
                .thinkEngineVersion(doc.getThinkEngineVersion())
                .goal(doc.getGoal())
                .status(doc.getStatus())
                .closeReason(doc.getCloseReason())
                .createdAt(doc.getCreatedAt())
                .build();
    }
}

package de.mhus.vance.brain.megadodo;

import de.mhus.vance.api.megadodo.MegadodoEventDto;
import de.mhus.vance.shared.megadodo.MegadodoEventDocument;

/**
 * Document → DTO for the feed. Public because more than the feed's own
 * controller returns these rows: the scheduler and hook views serve their
 * run history from Megadodo too, and a second hand-written mapper would
 * drift the moment a field is added.
 */
public final class MegadodoMapper {

    private MegadodoMapper() {
    }

    public static MegadodoEventDto toDto(MegadodoEventDocument doc) {
        return MegadodoEventDto.builder()
                .id(doc.getId())
                .timestamp(doc.getTimestamp())
                .action(doc.getAction())
                .phase(doc.getPhase())
                .severity(doc.getSeverity())
                .outcome(doc.getOutcome())
                .traceId(doc.getTraceId())
                .projectId(doc.getProjectId())
                .actor(doc.getActor())
                .refType(doc.getRefType())
                .refId(doc.getRefId())
                .message(doc.getMessage())
                .logPath(doc.getLogPath())
                .details(doc.getDetails() == null || doc.getDetails().isEmpty()
                        ? null : doc.getDetails())
                .build();
    }
}

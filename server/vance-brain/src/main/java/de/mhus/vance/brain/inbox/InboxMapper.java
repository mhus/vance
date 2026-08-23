package de.mhus.vance.brain.inbox;

import de.mhus.vance.api.inbox.MaximegalonDto;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import java.util.ArrayList;
import java.util.List;

/**
 * Conversion {@link MaximegalonDocument} → {@link MaximegalonDto} for
 * outbound WS frames. Persistent fields like {@code history} and
 * {@code version} are intentionally not exposed — the wire view is
 * the user-facing snapshot.
 */
public final class InboxMapper {

    private InboxMapper() {}

    public static MaximegalonDto toDto(MaximegalonDocument d) {
        return MaximegalonDto.builder()
                .id(d.getId())
                .tenantId(d.getTenantId())
                .originatorUserId(d.getOriginatorUserId())
                .assignedToUserId(d.getAssignedToUserId())
                .originProcessId(d.getOriginProcessId())
                .originSessionId(d.getOriginSessionId())
                .type(d.getType())
                .criticality(d.getCriticality())
                .tags(d.getTags() == null ? new ArrayList<>() : new ArrayList<>(d.getTags()))
                .title(d.getTitle())
                .body(d.getBody())
                .payload(d.getPayload())
                // effectRef stays server-side: it identifies a security
                // object, and the client only needs to know that an
                // effect exists to fetch its rendered description.
                .effectType(d.getEffectType())
                .status(d.getStatus())
                .requiresAction(d.isRequiresAction())
                .answer(d.getAnswer())
                .resolvedBy(d.getResolvedBy())
                .resolvedAt(d.getResolvedAt())
                .resolverReason(d.getResolverReason())
                .createdAt(d.getCreatedAt())
                .updatedAt(d.getUpdatedAt())
                .archivedAt(d.getArchivedAt())
                .build();
    }

    public static List<MaximegalonDto> toDtos(List<MaximegalonDocument> docs) {
        return docs.stream().map(InboxMapper::toDto).toList();
    }
}

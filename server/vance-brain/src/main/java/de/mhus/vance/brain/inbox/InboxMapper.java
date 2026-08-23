package de.mhus.vance.brain.inbox;

import de.mhus.vance.api.inbox.MaximegalonDto;
import de.mhus.vance.api.inbox.MaximegalonMessageDto;
import de.mhus.vance.api.inbox.MaximegalonReactionDto;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonMessage;
import de.mhus.vance.shared.inbox.MaximegalonReaction;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

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
                .teamId(d.getTeamId())
                .participants(copy(d.getParticipants()))
                .readBy(copy(d.getReadBy()))
                .reactions(toReactionDtos(d.getReactions()))
                // Empty on documents that came from the list query, which
                // projects the messages out — see MaximegalonService#listFiltered.
                // unreadFor is deliberately not mapped: it is a server-side
                // index for the badge count, and the client derives the same
                // thing from readBy when it has the thread open.
                .messages(toMessageDtos(d.getMessages()))
                .build();
    }

    private static List<String> copy(@Nullable List<String> source) {
        return source == null ? new ArrayList<>() : new ArrayList<>(source);
    }

    private static List<MaximegalonMessageDto> toMessageDtos(
            @Nullable List<MaximegalonMessage> messages) {
        if (messages == null) return new ArrayList<>();
        List<MaximegalonMessageDto> result = new ArrayList<>(messages.size());
        for (MaximegalonMessage m : messages) {
            result.add(MaximegalonMessageDto.builder()
                    .id(m.getId())
                    .authorUserId(m.getAuthorUserId())
                    .body(m.getBody())
                    .createdAt(m.getCreatedAt())
                    .parentId(m.getParentId())
                    .readBy(copy(m.getReadBy()))
                    .reactions(toReactionDtos(m.getReactions()))
                    .build());
        }
        return result;
    }

    private static List<MaximegalonReactionDto> toReactionDtos(
            @Nullable List<MaximegalonReaction> reactions) {
        if (reactions == null) return new ArrayList<>();
        List<MaximegalonReactionDto> result = new ArrayList<>(reactions.size());
        for (MaximegalonReaction r : reactions) {
            result.add(MaximegalonReactionDto.builder()
                    .key(r.getKey())
                    .userIds(copy(r.getUserIds()))
                    .build());
        }
        return result;
    }

    public static List<MaximegalonDto> toDtos(List<MaximegalonDocument> docs) {
        return docs.stream().map(InboxMapper::toDto).toList();
    }
}

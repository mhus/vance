package de.mhus.vance.shared.chat;

import de.mhus.vance.api.chat.ChatMessageDto;
import org.jspecify.annotations.Nullable;

/**
 * Document → DTO projection for chat messages. Every surface that ships a
 * conversation (session history, process transcript over WebSocket, process
 * transcript over REST) needs the same field set, so the mapping lives here
 * instead of once per controller.
 *
 * <p>{@code processName} is not on the document — it is the caller's
 * resolved process identity and therefore a parameter.
 */
public final class ChatMessageDtoMapper {

    private ChatMessageDtoMapper() {}

    public static ChatMessageDto toDto(ChatMessageDocument doc, @Nullable String processName) {
        return ChatMessageDto.builder()
                .messageId(doc.getId())
                .thinkProcessId(doc.getThinkProcessId())
                .processName(processName)
                .role(doc.getRole())
                .content(doc.getContent())
                .thinking(doc.getThinking())
                .createdAt(doc.getCreatedAt())
                .meta(doc.getMeta() == null || doc.getMeta().isEmpty() ? null : doc.getMeta())
                .senderUserId(doc.getSenderUserId())
                .senderDisplayName(doc.getSenderDisplayName())
                .addressedToAgent(doc.isAddressedToAgent())
                .build();
    }
}

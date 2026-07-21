package de.mhus.vance.brain.chat;

import de.mhus.vance.api.chat.ChatMessageDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pull endpoint for the conversation of a single think-process, addressed by its
 * Mongo id — the resolver behind a {@code vance-process:<id>} compose output
 * (the Damogran {@code agent} task's answer reference). Unlike
 * {@link ChatHistoryController} (which serves a <em>session</em>'s primary chat),
 * this serves an arbitrary process — e.g. a chatless Damogran agent living in a
 * system session — so the UI can render (and poll) the agent's latest answer.
 *
 * <p>Authorization is project-level ({@link Action#READ} on the process's
 * {@link Resource.Project}): the caller reached the agent by running a compose in
 * that project, and the agent's system session has no user owner to check.
 */
@RestController
@RequestMapping("/brain/{tenant}/process")
@RequiredArgsConstructor
public class ProcessMessagesController {

    /** Head-cut cap, matching {@link ChatHistoryController}'s scrollback limit. */
    private static final int DEFAULT_LIMIT = 500;

    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;
    private final RequestAuthority authority;

    @GetMapping("/{processId}/messages")
    public Map<String, Object> listMessages(
            @PathVariable("tenant") String tenant,
            @PathVariable("processId") String processId,
            HttpServletRequest request) {

        ThinkProcessDocument process = thinkProcessService.findById(processId)
                .filter(p -> tenant.equals(p.getTenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Process '" + processId + "' not found"));

        authority.enforce(request,
                new Resource.Project(tenant, process.getProjectId()), Action.READ);

        List<ChatMessageDocument> messages = chatMessageService.activeHistoryWithInterim(
                tenant, process.getSessionId(), processId);
        if (messages.size() > DEFAULT_LIMIT) {
            messages = messages.subList(messages.size() - DEFAULT_LIMIT, messages.size());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("processId", processId);
        out.put("status", process.getStatus() == null ? null : process.getStatus().name().toLowerCase());
        out.put("messages", messages.stream().map(ProcessMessagesController::toDto).toList());
        return out;
    }

    private static ChatMessageDto toDto(ChatMessageDocument doc) {
        return ChatMessageDto.builder()
                .messageId(doc.getId())
                .thinkProcessId(doc.getThinkProcessId())
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

package de.mhus.vance.brain.prompt;

import de.mhus.vance.brain.ai.VanceSystemMessage;
import de.mhus.vance.shared.memory.ScratchpadService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.ChatMessage;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Appends the {@link ScratchpadPromptBlock} to an engine's per-turn
 * message list. Separate from {@code PromptDateContextResolver} because
 * there is no session hop to lift here — the slots hang off the process
 * itself — and engines should be able to take the scratchpad block
 * without inheriting the date/client-environment lookups.
 *
 * <p>One indexed query per turn, covered by
 * {@code tenant_process_kind_time_idx} on {@code memories}.
 */
@Service
@RequiredArgsConstructor
public class ScratchpadPromptContributor {

    private final ScratchpadService scratchpadService;

    /**
     * No-op for a process that has taken no notes — see
     * {@link ScratchpadPromptBlock} for why the block is suppressed on an
     * empty inventory rather than rendering an invitation.
     */
    public void appendDynamicMessage(
            List<ChatMessage> messages, ThinkProcessDocument process) {
        String processId = process.getId();
        if (processId == null || processId.isBlank()) {
            return;
        }
        String body = ScratchpadPromptBlock.render(
                scratchpadService.list(process.getTenantId(), processId));
        if (body.isBlank()) {
            return;
        }
        messages.add(VanceSystemMessage.dynamic(body));
    }
}

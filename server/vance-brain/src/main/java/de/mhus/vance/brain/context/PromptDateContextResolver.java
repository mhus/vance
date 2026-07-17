package de.mhus.vance.brain.context;

import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.brain.prompt.PromptDateBlock;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.settings.TimezoneResolver;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.ChatMessage;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Lifts the {@code process → session → userId → display timezone}
 * lookup so engines can inject the {@code "Current date"} prompt block
 * in the <em>user's</em> timezone without each duplicating the session
 * hop. Mirrors {@link LanguageContextResolver} for language guidance.
 *
 * <p>Works headless: the scheduler / auto-wakeup paths stamp
 * {@code session.userId = runAs} at spawn, so the same lookup resolves
 * the owning user even when no client connection is open. When the user
 * has no timezone configured anywhere in the cascade, this degrades to
 * {@link TimezoneResolver#DEFAULT_ZONE} ({@code UTC}).
 */
@Service
@RequiredArgsConstructor
public class PromptDateContextResolver {

    private final TimezoneResolver timezoneResolver;
    private final SessionService sessionService;

    /**
     * Resolves the process owner's display timezone and appends the
     * {@link PromptDateBlock} dynamic system message. No-op when the
     * recipe opted out of date injection (see {@link PromptDateBlock}).
     */
    public void appendDynamicMessage(
            List<ChatMessage> messages,
            ThinkProcessDocument process,
            @Nullable ModelSize tier) {
        PromptDateBlock.appendDynamicMessage(messages, process, tier, resolveZone(process));
    }

    /**
     * The display {@link ZoneId} for the process owner, defaulting to
     * {@link TimezoneResolver#DEFAULT_ZONE} when unresolved.
     */
    public ZoneId resolveZone(ThinkProcessDocument process) {
        return timezoneResolver.zoneId(process.getTenantId(), resolveUserId(process));
    }

    private @Nullable String resolveUserId(ThinkProcessDocument process) {
        String sessionId = process.getSessionId();
        if (sessionId == null || sessionId.isBlank()) return null;
        return sessionService.findBySessionId(sessionId)
                .map(SessionDocument::getUserId)
                .orElse(null);
    }
}

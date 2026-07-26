package de.mhus.vance.brain.context;

import de.mhus.vance.api.ws.ClientContext;
import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.brain.ai.VanceSystemMessage;
import de.mhus.vance.brain.prompt.PromptDateBlock;
import de.mhus.vance.brain.prompt.PromptEnvironmentBlock;
import de.mhus.vance.brain.tools.client.ClientToolRegistry;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.VanceHandshakeInterceptor;
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
import org.springframework.web.socket.WebSocketSession;

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
 *
 * <p>Also lifts the parallel {@code process → session → live client
 * connection → client platform} lookup for the {@link
 * PromptEnvironmentBlock} — unlike the timezone (a persistent setting),
 * the client environment is ephemeral, present only while a CLIENT
 * work-target connection ({@code vance-foot}) is bound, and absent for
 * headless and web turns.
 */
@Service
@RequiredArgsConstructor
public class PromptDateContextResolver {

    private final TimezoneResolver timezoneResolver;
    private final SessionService sessionService;
    private final ClientToolRegistry clientToolRegistry;

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

    /**
     * Appends the {@link PromptEnvironmentBlock} for the process's live
     * client connection as a dynamic system message. No-op when no CLIENT
     * work-target connection is bound (headless / web turns) or the client
     * sent no {@code ClientContext} — so the block appears exactly when the
     * LLM may drive {@code client_exec_run} / {@code client_file_*} on a
     * real client and needs to target its platform.
     */
    public void appendClientEnvMessage(List<ChatMessage> messages, ThinkProcessDocument process) {
        ClientContext client = resolveClientContext(process);
        if (client == null) return;
        String body = PromptEnvironmentBlock.render(client);
        if (body.isBlank()) return;
        messages.add(VanceSystemMessage.dynamic(body));
    }

    private @Nullable ClientContext resolveClientContext(ThinkProcessDocument process) {
        String sessionId = process.getSessionId();
        if (sessionId == null || sessionId.isBlank()) return null;
        WebSocketSession wsSession = clientToolRegistry.entry(sessionId)
                .map(ClientToolRegistry.Entry::wsSession)
                .orElse(null);
        if (wsSession == null) return null;
        Object raw = wsSession.getAttributes().get(VanceHandshakeInterceptor.ATTR_CONNECTION);
        if (!(raw instanceof ConnectionContext connection)) return null;
        return connection.getClientContext();
    }
}

package de.mhus.vance.brain.context;

import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.settings.LanguageResolver;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Convenience wrapper that resolves the active {@code chat.language}
 * and {@code content.language} settings for a {@link ThinkProcessDocument}
 * and renders them as a Markdown {@code ## Languages} block ready to
 * concatenate onto a system-prompt body.
 *
 * <p>Exists because engines that don't go through
 * {@link de.mhus.vance.brain.memory.MemoryContextLoader} (Slartibartfast
 * phases, Zaphod synthesis) still need the same language guidance the
 * memory context injects for Arthur/Eddie/Ford. The actual resolution
 * lives in {@link LanguageResolver}; this helper just lifts the
 * session→userId lookup so callers can stay terse.
 */
@Service
@RequiredArgsConstructor
public class LanguageContextResolver {

    private final LanguageResolver languageResolver;
    private final SessionService sessionService;

    /**
     * Returns the Markdown block (with trailing newline) or an empty
     * string when neither language setting is configured anywhere in
     * the cascade. Concatenate unconditionally — an empty suffix
     * means "no opinion".
     */
    public String formatBlock(ThinkProcessDocument process) {
        @Nullable String userId = resolveUserId(process);
        return languageResolver.formatLanguageBlock(
                process.getTenantId(), userId,
                process.getProjectId(), process.getId());
    }

    private @Nullable String resolveUserId(ThinkProcessDocument process) {
        String sessionId = process.getSessionId();
        if (sessionId == null || sessionId.isBlank()) return null;
        return sessionService.findBySessionId(sessionId)
                .map(SessionDocument::getUserId)
                .orElse(null);
    }
}

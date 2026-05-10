package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.api.thinkprocess.ProcessMode;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Resolves an engine's "default" system prompt through the document
 * cascade (project → {@code _vance} → {@code classpath:vance-defaults/<path>})
 * so tenants and users can override hardcoded prompts without touching
 * source. The returned text is a Pebble template — the caller renders
 * it with the per-turn context via
 * {@link de.mhus.vance.brain.prompt.PromptTemplateRenderer} (typically
 * by handing it to {@link SystemPrompts#compose} which renders both
 * the engine default and the recipe override together).
 *
 * <p>Tier / size variation has moved into the template body
 * ({@code {% if tier == "small" %}…{% endif %}}) — there is no more
 * automatic {@code -small.md} suffix lookup. Mode-suffix lookup
 * remains, because Plan-Mode prompts (Arthur) differ enough that
 * separate files are clearer than a giant {@code if/elseif} chain.
 *
 * <p>Convention: prompts live under {@code prompts/<engine>-prompt.md}.
 * Plan-Mode-capable engines additionally ship
 * {@code prompts/<engine>-prompt-{exploring,planning,executing}.md}
 * — see {@link #modeVariantPath(String, ProcessMode)}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EnginePromptResolver {

    private final DocumentService documentService;
    private final SessionService sessionService;

    /**
     * @param process       the running think-process — its tenant and
     *                      project drive the cascade
     * @param promptPath    document path relative to project / _vance /
     *                      classpath, e.g. {@code prompts/eddie-prompt.md}.
     *                      {@code null} or blank skips the lookup
     *                      (returns {@code javaFallback} immediately).
     * @param javaFallback  hardcoded prompt to return when neither the
     *                      cascade nor the classpath carry the file
     */
    public String resolve(
            ThinkProcessDocument process,
            @Nullable String promptPath,
            String javaFallback) {
        if (promptPath == null || promptPath.isBlank() || process == null
                || process.getTenantId() == null || process.getTenantId().isBlank()) {
            return javaFallback;
        }
        @Nullable String projectId = resolveProjectId(process);
        return documentService.lookupCascade(process.getTenantId(), projectId, promptPath)
                .map(LookupResult::content)
                .filter(s -> s != null && !s.isBlank())
                .orElse(javaFallback);
    }

    /**
     * Mode-aware resolution for Plan-Mode-capable engines. Resolution
     * order:
     * <ol>
     *   <li>Mode-suffixed variant ({@code <base>-exploring.md} etc.)
     *       when {@code mode != NORMAL}.</li>
     *   <li>Base path ({@code <base>.md}).</li>
     *   <li>{@code javaFallback}.</li>
     * </ol>
     */
    public String resolveForMode(
            ThinkProcessDocument process,
            String basePath,
            @Nullable ProcessMode mode,
            String javaFallback) {
        if (basePath == null || basePath.isBlank() || process == null
                || process.getTenantId() == null || process.getTenantId().isBlank()) {
            return javaFallback;
        }
        @Nullable String projectId = resolveProjectId(process);
        if (mode != null && mode != ProcessMode.NORMAL) {
            String modePath = modeVariantPath(basePath, mode);
            if (modePath != null && !modePath.equals(basePath)) {
                @Nullable String hit = lookup(
                        process.getTenantId(), projectId, modePath);
                if (hit != null) return hit;
            }
        }
        @Nullable String baseHit = lookup(process.getTenantId(), projectId, basePath);
        return baseHit != null ? baseHit : javaFallback;
    }

    private @Nullable String lookup(String tenantId, @Nullable String projectId, String path) {
        return documentService.lookupCascade(tenantId, projectId, path)
                .map(LookupResult::content)
                .filter(s -> s != null && !s.isBlank())
                .orElse(null);
    }

    /**
     * Tenant-scoped variant for callers that don't have a
     * {@link ThinkProcessDocument} yet (e.g. {@code EngineBundledConfig}
     * factories at spawn time, where the engine resolves defaults for
     * a fresh process). Skips the project layer of the cascade.
     */
    public String resolveForTenant(
            String tenantId,
            @Nullable String projectId,
            @Nullable String promptPath,
            String javaFallback) {
        if (promptPath == null || promptPath.isBlank()
                || tenantId == null || tenantId.isBlank()) {
            return javaFallback;
        }
        return documentService.lookupCascade(tenantId, projectId, promptPath)
                .map(LookupResult::content)
                .filter(s -> s != null && !s.isBlank())
                .orElse(javaFallback);
    }

    /**
     * Mode-suffixed path variant for Plan-Mode prompts.
     * {@code EXPLORING} → {@code <base>-exploring.md},
     * {@code PLANNING}  → {@code <base>-planning.md},
     * {@code EXECUTING} → {@code <base>-executing.md}.
     * {@code NORMAL} returns the base path as-is.
     *
     * <p>See {@code specification/plan-mode.md} §7.
     */
    public static @Nullable String modeVariantPath(
            @Nullable String basePath, @Nullable ProcessMode mode) {
        if (basePath == null || mode == null || mode == ProcessMode.NORMAL) {
            return basePath;
        }
        String suffix = switch (mode) {
            case EXPLORING -> "-exploring";
            case PLANNING -> "-planning";
            case EXECUTING -> "-executing";
            default -> "";
        };
        if (suffix.isEmpty()) return basePath;
        int dot = basePath.lastIndexOf('.');
        if (dot < 0) return basePath + suffix;
        return basePath.substring(0, dot) + suffix + basePath.substring(dot);
    }

    private @Nullable String resolveProjectId(ThinkProcessDocument process) {
        String sessionId = process.getSessionId();
        if (sessionId == null || sessionId.isBlank()) return null;
        return sessionService.findBySessionId(sessionId)
                .map(SessionDocument::getProjectId)
                .filter(p -> p != null && !p.isBlank())
                .orElse(null);
    }
}

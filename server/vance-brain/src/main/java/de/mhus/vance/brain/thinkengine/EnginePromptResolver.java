package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.api.thinkprocess.ProcessMode;
import de.mhus.vance.brain.ai.ModelSize;
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
 * cascade so tenants and users can override hardcoded prompts without
 * touching source. Conversation engines that already drive their full
 * prompt through {@link SystemPrompts#compose} treat the resolved value
 * as the {@code engineDefault} input to that helper — so a recipe
 * {@code promptOverride} still wins on top of whatever this resolver
 * returns.
 *
 * <p>Resolution order per call:
 * <ol>
 *   <li>{@link DocumentService#lookupCascade(String, String, String)}
 *       on {@code promptPath}, which itself walks
 *       project → {@code _vance} → {@code classpath:vance-defaults/<path>}</li>
 *   <li>The {@code javaFallback} string passed by the caller — used when
 *       no document matched (e.g. classpath resource missing in test
 *       setups).</li>
 * </ol>
 *
 * <p>Convention: prompts live under {@code prompts/<engine>-<variant>.md}.
 * For variant-aware engines (Eddie has {@code -small} for small models)
 * pass the variant-suffixed path directly — this resolver does not derive
 * variants. Use {@link #variantPath(String, ModelSize)} as a small helper
 * if needed.
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
     * Tier-aware resolution: SMALL models get a {@code -small} variant
     * when one exists, otherwise fall through to the base path. Recipes
     * may pin a different small-variant path explicitly via
     * {@code promptDocumentSmall} — pass it as {@code smallOverridePath}.
     *
     * <p>Resolution order for {@code modelSize == SMALL}:
     * <ol>
     *   <li>{@code smallOverridePath} when non-blank — exact path, no
     *       further variant derivation.</li>
     *   <li>{@code variantPath(basePath, SMALL)} — i.e.
     *       {@code prompts/foo-small.md} for {@code prompts/foo.md}.</li>
     *   <li>{@code basePath} — natural fall-through when no small
     *       variant exists.</li>
     *   <li>{@code javaFallback} — last resort.</li>
     * </ol>
     *
     * <p>For non-SMALL model sizes only (2) and (4) of the above apply
     * — really just the base path with the Java fallback.
     */
    public String resolveTiered(
            ThinkProcessDocument process,
            String basePath,
            @Nullable String smallOverridePath,
            ModelSize modelSize,
            String javaFallback) {
        if (basePath == null || basePath.isBlank() || process == null
                || process.getTenantId() == null || process.getTenantId().isBlank()) {
            return javaFallback;
        }
        @Nullable String projectId = resolveProjectId(process);
        if (modelSize == ModelSize.SMALL) {
            // (1) explicit small override from the recipe
            if (smallOverridePath != null && !smallOverridePath.isBlank()) {
                @Nullable String overrideHit = lookup(
                        process.getTenantId(), projectId, smallOverridePath);
                if (overrideHit != null) return overrideHit;
            }
            // (2) auto-derived -small variant
            String autoSmall = variantPath(basePath, ModelSize.SMALL);
            if (autoSmall != null && !autoSmall.equals(basePath)) {
                @Nullable String autoHit = lookup(
                        process.getTenantId(), projectId, autoSmall);
                if (autoHit != null) return autoHit;
            }
        }
        // (3) base path — works for both SMALL (no -small available) and LARGE
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
     * Convenience: appends a {@code -small} suffix before the extension
     * when {@code modelSize == SMALL}, otherwise returns the path as-is.
     * E.g. {@code prompts/eddie-prompt.md} →
     * {@code prompts/eddie-prompt-small.md} for the small variant.
     * Returns {@code null} when the input is {@code null}.
     */
    public static @Nullable String variantPath(@Nullable String basePath, ModelSize modelSize) {
        if (basePath == null || modelSize != ModelSize.SMALL) return basePath;
        int dot = basePath.lastIndexOf('.');
        if (dot < 0) return basePath + "-small";
        return basePath.substring(0, dot) + "-small" + basePath.substring(dot);
    }

    /**
     * Mode-suffixed path variant for Plan-Mode prompts.
     * {@code EXPLORING} → {@code <base>-exploring.md},
     * {@code PLANNING}  → {@code <base>-planning.md}.
     * {@code NORMAL} and {@code EXECUTING} return the base path as-is.
     *
     * <p>See {@code readme/arthur-plan-mode.md} §7.
     */
    public static @Nullable String modeVariantPath(
            @Nullable String basePath, @Nullable ProcessMode mode) {
        if (basePath == null || mode == null
                || mode == ProcessMode.NORMAL || mode == ProcessMode.EXECUTING) {
            return basePath;
        }
        String suffix = switch (mode) {
            case EXPLORING -> "-exploring";
            case PLANNING -> "-planning";
            default -> "";
        };
        if (suffix.isEmpty()) return basePath;
        int dot = basePath.lastIndexOf('.');
        if (dot < 0) return basePath + suffix;
        return basePath.substring(0, dot) + suffix + basePath.substring(dot);
    }

    /**
     * Tier-aware <em>and</em> mode-aware resolution. Used by Plan-Mode-
     * capable engines (Arthur). Resolution order:
     * <ol>
     *   <li>Mode-suffixed variant ({@code -exploring} / {@code -planning})
     *       if applicable, with {@link ModelSize#SMALL} small-variant
     *       lookup as inner cascade.</li>
     *   <li>Mode-suffixed variant without small-suffix.</li>
     *   <li>Falls through to {@link #resolveTiered} on the base path
     *       (size-aware, no mode suffix).</li>
     * </ol>
     *
     * <p>For {@link ProcessMode#NORMAL}/{@link ProcessMode#EXECUTING}
     * this is identical to {@link #resolveTiered}.
     */
    public String resolveTieredForMode(
            ThinkProcessDocument process,
            String basePath,
            @Nullable String smallOverridePath,
            ModelSize modelSize,
            @Nullable ProcessMode mode,
            String javaFallback) {
        if (basePath == null || basePath.isBlank() || process == null
                || process.getTenantId() == null || process.getTenantId().isBlank()) {
            return javaFallback;
        }
        if (mode != null && mode != ProcessMode.NORMAL && mode != ProcessMode.EXECUTING) {
            String modePath = modeVariantPath(basePath, mode);
            if (modePath != null && !modePath.equals(basePath)) {
                @Nullable String projectId = resolveProjectId(process);
                if (modelSize == ModelSize.SMALL) {
                    String modeSmall = variantPath(modePath, ModelSize.SMALL);
                    if (modeSmall != null && !modeSmall.equals(modePath)) {
                        @Nullable String hit = lookup(
                                process.getTenantId(), projectId, modeSmall);
                        if (hit != null) return hit;
                    }
                }
                @Nullable String hit = lookup(
                        process.getTenantId(), projectId, modePath);
                if (hit != null) return hit;
            }
        }
        // Fall through to size-only cascade on the base path.
        return resolveTiered(process, basePath, smallOverridePath, modelSize, javaFallback);
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

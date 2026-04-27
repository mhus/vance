package de.mhus.vance.brain.ai;

import de.mhus.vance.shared.settings.SettingService;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Translates a model spec — direct {@code provider:model} or alias
 * {@code namespace:key} — into a concrete (provider, model) pair.
 *
 * <h2>Why aliases</h2>
 * Recipes ship in the brain JAR and can't be edited per-deployment.
 * If they hard-coded {@code anthropic:claude-sonnet-4-5}, every
 * tenant would need an Anthropic key, and the recipe would break the
 * day Anthropic deprecates the model. By indirecting through
 * <em>aliases</em> stored in settings, recipes reference a logical
 * name like {@code default:analyze}; tenants point that at whatever
 * provider/model they actually have access to today.
 *
 * <h2>Resolution rules</h2>
 * Given input {@code <prefix>:<rest>}:
 * <ol>
 *   <li>If {@code prefix} is a registered AI provider, return
 *       {@code (prefix, rest)} directly. Lets callers keep using
 *       {@code anthropic:claude-sonnet-4-5} when they really mean
 *       it.</li>
 *   <li>Otherwise look up
 *       {@code ai.alias.<prefix>.<rest>} in the tenant's settings
 *       and recurse with that value.</li>
 *   <li>If the alias is unknown and {@code prefix} equals
 *       {@code "default"}, fall back to the tenant's
 *       {@code ai.default.provider}/{@code ai.default.model} pair —
 *       so out-of-the-box recipes work even when no aliases are
 *       configured yet.</li>
 *   <li>Otherwise throw — better than silently picking a model
 *       the operator didn't intend.</li>
 * </ol>
 *
 * <p>Cycle-protected: a chain like {@code default:foo → cheap:foo →
 * default:foo} is detected and reported.
 *
 * <h2>Example settings</h2>
 * <pre>{@code
 * ai.alias.default.fast    = gemini:gemini-2.5-flash
 * ai.alias.default.analyze = anthropic:claude-sonnet-4-5
 * ai.alias.default.deep    = anthropic:claude-opus-4-7
 * ai.alias.cheap.lookup    = gemini:gemini-2.5-flash
 * }</pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiModelResolver {

    private static final String SETTINGS_REF_TYPE = "tenant";
    private static final String ALIAS_KEY_PREFIX = "ai.alias.";
    private static final String DEFAULT_PROVIDER_KEY = "ai.default.provider";
    private static final String DEFAULT_MODEL_KEY = "ai.default.model";
    private static final String DEFAULT_NAMESPACE = "default";
    private static final int MAX_DEPTH = 8;

    private final AiModelService aiModelService;
    private final SettingService settingService;

    /** Materialised endpoint of the resolution. */
    public record Resolved(String provider, String modelName) {}

    /**
     * Resolves a non-null model spec. Falls through to the tenant
     * default when the value is the literal string {@code "default"}
     * with no rest.
     */
    public Resolved resolve(String input, String tenantId) {
        if (input == null || input.isBlank()) {
            return tenantDefault(tenantId, "<missing input>");
        }
        return resolve(input.trim(), tenantId, new LinkedHashSet<>());
    }

    /**
     * Resolves an optional model spec. When {@code input} is
     * {@code null} or blank, returns the tenant default
     * ({@code ai.default.provider}/{@code ai.default.model}). Used
     * by engines whose {@code params.model} may or may not be set.
     */
    public Resolved resolveOrDefault(@Nullable String input, String tenantId) {
        if (input == null || input.isBlank()) {
            return tenantDefault(tenantId, "<no override>");
        }
        return resolve(input.trim(), tenantId, new LinkedHashSet<>());
    }

    private Resolved resolve(String input, String tenantId, Set<String> seen) {
        if (!seen.add(input)) {
            throw new UnknownModelException(
                    "Alias cycle detected: " + String.join(" → ", seen) + " → " + input);
        }
        if (seen.size() > MAX_DEPTH) {
            throw new UnknownModelException(
                    "Alias resolution exceeded depth " + MAX_DEPTH
                            + ": " + String.join(" → ", seen));
        }
        int colon = input.indexOf(':');
        if (colon <= 0 || colon == input.length() - 1) {
            throw new UnknownModelException(
                    "Model spec '" + input + "' must be '<provider>:<model>' or "
                            + "'<alias-namespace>:<key>'");
        }
        String prefix = input.substring(0, colon).trim();
        String rest = input.substring(colon + 1).trim();
        if (prefix.isEmpty() || rest.isEmpty()) {
            throw new UnknownModelException(
                    "Model spec '" + input + "' has empty prefix or suffix");
        }

        // Direct provider+model — done.
        if (aiModelService.hasProvider(prefix)) {
            return new Resolved(prefix, rest);
        }

        // Alias lookup.
        String settingKey = ALIAS_KEY_PREFIX + prefix + "." + rest;
        @Nullable String aliased = settingService.getStringValue(
                tenantId, SETTINGS_REF_TYPE, tenantId, settingKey);
        if (aliased != null && !aliased.isBlank()) {
            log.debug("AiModelResolver: alias '{}' → '{}'", input, aliased);
            return resolve(aliased.trim(), tenantId, seen);
        }

        // Fallback for the `default:` namespace — keeps out-of-the-box
        // recipes working when the operator hasn't yet differentiated
        // aliases.
        if (DEFAULT_NAMESPACE.equals(prefix)) {
            log.debug("AiModelResolver: alias '{}' not configured, falling back to tenant default",
                    input);
            return tenantDefault(tenantId, input);
        }

        throw new UnknownModelException(
                "Unknown model spec '" + input + "' — neither a registered "
                        + "provider nor a configured alias. Known providers: "
                        + aiModelService.listProviders()
                        + "; expected setting: '" + settingKey + "'");
    }

    private Resolved tenantDefault(String tenantId, String triggeredBy) {
        @Nullable String provider = settingService.getStringValue(
                tenantId, SETTINGS_REF_TYPE, tenantId, DEFAULT_PROVIDER_KEY);
        @Nullable String model = settingService.getStringValue(
                tenantId, SETTINGS_REF_TYPE, tenantId, DEFAULT_MODEL_KEY);
        if (provider == null || provider.isBlank()
                || model == null || model.isBlank()) {
            throw new UnknownModelException(
                    "Cannot resolve '" + triggeredBy + "': tenant '" + tenantId
                            + "' has no '" + DEFAULT_PROVIDER_KEY + "' / '"
                            + DEFAULT_MODEL_KEY + "' settings");
        }
        return new Resolved(provider, model);
    }

    /** Thrown when a model spec cannot be resolved. */
    public static class UnknownModelException extends RuntimeException {
        public UnknownModelException(String message) {
            super(message);
        }
    }
}

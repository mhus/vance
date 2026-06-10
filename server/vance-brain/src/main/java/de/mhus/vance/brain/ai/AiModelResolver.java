package de.mhus.vance.brain.ai;

import de.mhus.vance.shared.settings.SettingService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 *       {@code (prefix, prefix, rest)} directly — instance equals the
 *       protocol wire-name. Lets callers keep using
 *       {@code anthropic:claude-sonnet-4-5} when they really mean
 *       it.</li>
 *   <li>Otherwise if {@code ai.provider.<prefix>.type} is set, treat
 *       {@code prefix} as a <em>named provider instance</em>: return
 *       {@code (type, prefix, rest)}. The same protocol can back
 *       multiple instances with different credentials / base URLs
 *       (e.g. a {@code deepseek-direct} instance on top of the
 *       OpenAI wire). Unknown {@code type} fails fast.</li>
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
 * <h2>Comma-cascade</h2>
 * Any spec string accepted by this resolver may be a
 * <em>comma-separated cascade</em> of elements: the first element
 * whose alias/instance/provider is <em>configured</em> wins. Example:
 * {@code default:arthur,default:chat} — uses an engine-specific alias
 * when defined, otherwise falls through to the generic chat alias. A
 * single-element spec (no comma) behaves identically to the pre-cascade
 * version.
 *
 * <p>The {@code default:}-safety-net (rule 4 above) only fires for the
 * <strong>last</strong> element of the cascade — non-last elements that
 * are undefined simply skip to the next. Runtime failures (provider
 * down, quota exhausted) are <em>not</em> cascade triggers; they still
 * propagate as hard errors. Quota-/disable-driven runtime selection
 * lives in {@code ChatBehaviorBuilder}'s {@code params.fallbackModels}
 * chain (orthogonal mechanism).
 *
 * <p>Cascade syntax also applies inside alias <em>target</em> values:
 * {@code ai.alias.default.chat = anthropic:claude-haiku-4-5,openai:gpt-4o-mini}
 * is valid. Cycle-detection and depth-limit hold across cascade levels.
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

    private static final String ALIAS_KEY_PREFIX = "ai.alias.";
    private static final String PROVIDER_TYPE_KEY_FMT = "ai.provider.%s.type";
    private static final String DEFAULT_PROVIDER_KEY = "ai.default.provider";
    private static final String DEFAULT_MODEL_KEY = "ai.default.model";
    private static final String DEFAULT_NAMESPACE = "default";
    private static final int MAX_DEPTH = 8;

    private final AiModelService aiModelService;
    private final SettingService settingService;

    /**
     * Materialised endpoint of the resolution.
     *
     * @param provider         protocol wire-name ({@link ProviderType#wireName()}) —
     *                         drives adapter dispatch in {@link AiModelService}.
     * @param providerInstance instance label — used by {@code ChatBehaviorBuilder}
     *                         to read {@code ai.provider.<instance>.apiKey}/
     *                         {@code .baseUrl}, and by {@code ModelCatalog} to
     *                         look up metadata. Equals {@code provider} for direct
     *                         ProviderType specs; differs only when the spec
     *                         referenced a {@link Resolved named instance}
     *                         configured via {@code ai.provider.<instance>.type}.
     * @param modelName        wire model name handed to the provider API.
     */
    public record Resolved(String provider, String providerInstance, String modelName) {
        /** Back-compat factory for direct ProviderType specs where instance == provider. */
        public static Resolved direct(String provider, String modelName) {
            return new Resolved(provider, provider, modelName);
        }
    }

    /**
     * Resolves a non-null model spec. Falls through to the tenant
     * default when the value is the literal string {@code "default"}
     * with no rest.
     *
     * <p>{@code projectId}/{@code processId} drive the project cascade
     * for alias and default lookups; pass {@code null} to read from
     * {@code _vance} only.
     */
    public Resolved resolve(
            String input,
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId) {
        if (input == null || input.isBlank()) {
            return tenantDefault(tenantId, projectId, processId, "<missing input>");
        }
        return resolveCascade(
                input.trim(), tenantId, projectId, processId, new LinkedHashSet<>());
    }

    /**
     * Resolves an optional model spec. When {@code input} is
     * {@code null} or blank, returns the project-cascade default
     * ({@code ai.default.provider}/{@code ai.default.model}, looked up
     * via {@code getStringValueCascade}). Used by engines whose
     * {@code params.model} may or may not be set.
     *
     * <p>{@code projectId}/{@code processId} may be {@code null} —
     * the corresponding cascade layers are skipped.
     */
    public Resolved resolveOrDefault(
            @Nullable String input,
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId) {
        if (input == null || input.isBlank()) {
            return tenantDefault(tenantId, projectId, processId, "<no override>");
        }
        return resolveCascade(
                input.trim(), tenantId, projectId, processId, new LinkedHashSet<>());
    }

    /**
     * Splits the input on commas and resolves elements left-to-right.
     * Non-last elements whose alias/instance/provider is undefined return
     * {@code null} from {@link #resolveElement} and we move on; the last
     * element resolves with full single-element semantics, including the
     * {@code default:}-safety-net.
     *
     * <p>Each cascade element starts from a copy of the inherited
     * {@code seen} set so independent alias-chains don't pollute each
     * other's cycle detection.
     */
    private Resolved resolveCascade(
            String input,
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId,
            Set<String> seen) {
        List<String> elements = splitCascade(input);
        if (elements.isEmpty()) {
            throw new UnknownModelException(
                    "Model spec '" + input + "' has no usable elements");
        }
        int lastIdx = elements.size() - 1;
        for (int i = 0; i < lastIdx; i++) {
            @Nullable Resolved r = resolveElement(
                    elements.get(i), tenantId, projectId, processId,
                    new LinkedHashSet<>(seen), false);
            if (r != null) {
                return r;
            }
        }
        @Nullable Resolved last = resolveElement(
                elements.get(lastIdx), tenantId, projectId, processId,
                new LinkedHashSet<>(seen), true);
        if (last == null) {
            // resolveElement with lastInCascade=true never returns null —
            // it either resolves, falls back to tenantDefault, or throws.
            throw new UnknownModelException(
                    "Cascade '" + input + "' exhausted with no resolution");
        }
        return last;
    }

    /**
     * Resolves a single cascade element. Returns {@code null} when
     * {@code lastInCascade=false} and the element is undefined (signals
     * the caller to try the next cascade element). When
     * {@code lastInCascade=true}, applies the documented single-element
     * semantics: {@code default:}-safety-net for unknown aliases, throws
     * for other namespaces.
     */
    private @Nullable Resolved resolveElement(
            String input,
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId,
            Set<String> seen,
            boolean lastInCascade) {
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
            return Resolved.direct(prefix, rest);
        }

        // Named provider instance — `ai.provider.<prefix>.type` binds the
        // free-form instance label to a concrete ProviderType wire-name.
        // Lets tenants configure multiple OpenAI-compatible endpoints
        // (e.g. real OpenAI plus a deepseek-direct instance) without
        // overloading the protocol wire-name.
        String instanceTypeKey = String.format(PROVIDER_TYPE_KEY_FMT, prefix);
        @Nullable String instanceType = settingService.getStringValueCascade(
                tenantId, projectId, processId, instanceTypeKey);
        if (instanceType != null && !instanceType.isBlank()) {
            String typeWireName = instanceType.trim();
            if (!aiModelService.hasProvider(typeWireName)) {
                throw new UnknownModelException(
                        "Provider instance '" + prefix + "' declares unknown type '"
                                + typeWireName + "' (setting '" + instanceTypeKey
                                + "'). Known providers: " + aiModelService.listProviders());
            }
            log.debug("AiModelResolver: instance '{}' → type '{}'", prefix, typeWireName);
            return new Resolved(typeWireName, prefix, rest);
        }

        // Alias lookup — project cascade. Alias target may itself be a
        // comma-cascade, so route through resolveCascade rather than
        // resolveElement directly.
        String settingKey = ALIAS_KEY_PREFIX + prefix + "." + rest;
        @Nullable String aliased = settingService.getStringValueCascade(
                tenantId, projectId, processId, settingKey);
        if (aliased != null && !aliased.isBlank()) {
            log.debug("AiModelResolver: alias '{}' → '{}'", input, aliased);
            return resolveCascade(aliased.trim(), tenantId, projectId, processId, seen);
        }

        // Alias undefined — non-last cascade elements skip to the next.
        if (!lastInCascade) {
            return null;
        }

        // Last element: safety net for `default:` namespace, else throw.
        if (DEFAULT_NAMESPACE.equals(prefix)) {
            log.debug("AiModelResolver: alias '{}' not configured, falling back to tenant default",
                    input);
            return tenantDefault(tenantId, projectId, processId, input);
        }

        throw new UnknownModelException(
                "Unknown model spec '" + input + "' — neither a registered "
                        + "provider nor a configured alias. Known providers: "
                        + aiModelService.listProviders()
                        + "; expected setting: '" + settingKey + "'");
    }

    /**
     * Normalises {@code params.model} / {@code params.provider} from an
     * engine-params map into a spec string suitable for
     * {@link #resolve} / {@link #resolveOrDefault}. Returns {@code null}
     * when no model is configured — caller should fall through to
     * tenant default.
     *
     * <p>Accepted shapes (single source of truth for both
     * {@code ChatBehaviorBuilder.readModelSpec} and
     * {@code LightLlmServiceImpl}):
     * <ul>
     *   <li>{@code params.model = "provider:model"} or
     *       {@code "alias:key"} — returned as-is, including comma-cascade
     *       forms like {@code "default:a,default:b"}.</li>
     *   <li>{@code params.model} + {@code params.provider} (legacy
     *       split-params) → {@code "provider:model"}.</li>
     *   <li>{@code params.model = "shortname"} (no colon, no provider) →
     *       {@code "default:shortname"}.</li>
     *   <li>Missing/blank/non-string {@code params.model} → {@code null}.</li>
     * </ul>
     */
    public static @Nullable String parseModelSpec(@Nullable Map<String, Object> params) {
        if (params == null) {
            return null;
        }
        String model = stringValue(params.get("model"));
        String provider = stringValue(params.get("provider"));
        if (model == null) {
            return null;
        }
        if (model.contains(":")) {
            return model;
        }
        if (provider != null) {
            return provider + ":" + model;
        }
        return "default:" + model;
    }

    private static @Nullable String stringValue(@Nullable Object v) {
        return (v instanceof String s && !s.isBlank()) ? s.trim() : null;
    }

    /**
     * Parses a comma-separated cascade spec into its trimmed,
     * non-empty elements. Tolerates incidental whitespace
     * ({@code "a , b"} ≡ {@code "a,b"}) and empty entries
     * ({@code "a,,b"} → {@code [a, b]}).
     */
    private static List<String> splitCascade(String input) {
        String[] parts = input.split(",");
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private Resolved tenantDefault(
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId,
            String triggeredBy) {
        @Nullable String provider = settingService.getStringValueCascade(
                tenantId, projectId, processId, DEFAULT_PROVIDER_KEY);
        @Nullable String model = settingService.getStringValueCascade(
                tenantId, projectId, processId, DEFAULT_MODEL_KEY);
        if (provider == null || provider.isBlank()
                || model == null || model.isBlank()) {
            throw new UnknownModelException(
                    "Cannot resolve '" + triggeredBy + "': tenant '" + tenantId
                            + "' has no '" + DEFAULT_PROVIDER_KEY + "' / '"
                            + DEFAULT_MODEL_KEY + "' settings");
        }
        return Resolved.direct(provider, model);
    }

    /** Thrown when a model spec cannot be resolved. */
    public static class UnknownModelException extends RuntimeException {
        public UnknownModelException(String message) {
            super(message);
        }
    }
}

package de.mhus.vance.anus.setup;

import de.mhus.vance.shared.tenant.TenantService;
import de.mhus.vance.shared.user.UserService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Parses the agent-supplied tenant-setup config (YAML) into a typed
 * {@link Parsed} record — the non-interactive twin of the wizard's prompts
 * and menus. Spec: {@code specification/setup-agent-mode.md}.
 *
 * <p>Config shape (kebab-case throughout, mirroring the interactive wizard's
 * entries):
 * <pre>
 * tenant:
 *   name: acme            # required, not '_vance'
 *   title: Acme Corp      # optional display name
 * user:
 *   name: admin           # required, no service-account ('_') prefix
 *   title: Mara Miller    # optional
 *   email: ops@example.com # optional
 *   password: secret      # required when the user does not exist yet
 * ai:                     # optional — absent / provider 'none' skips AI
 *   provider: gemini | openai | anthropic | custom | none
 *   instance: cortecs     # custom only — the settings namespace
 *   model: deepseek-chat   # required for custom, preset default otherwise
 *   base-url: https://…   # custom only, required
 *   api-key: sk-…         # required when the tenant is new
 *   embedding-api-key: …  # optional, reuses api-key when blank
 * serper-key: …           # optional web-research key
 * </pre>
 *
 * <p>Same rules as the wizard menus, made fail-closed: unknown keys, wrong
 * types and missing required values are collected <b>all at once</b> and
 * reported as one list — an agent gets a single round-trip to see every
 * problem. Nothing here touches the database; existence checks (does the
 * tenant/user exist — the "ensure" semantics) live in
 * {@link SetupWizard#runHeadless}.
 */
final class SetupConfigParser {

    /** All collected config problems, one per line. */
    static final class SetupConfigException extends RuntimeException {

        private final transient List<String> problems;

        SetupConfigException(List<String> problems) {
            super(String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        List<String> problems() {
            return problems;
        }
    }

    /** Typed result of a successful parse; validation against the DB happens later. */
    record Parsed(
            String tenantName,
            @Nullable String tenantTitle,
            String userName,
            @Nullable String userTitle,
            @Nullable String userEmail,
            @Nullable String userPassword,
            @Nullable ProviderPreset provider,
            @Nullable String instanceName,
            String aiModel,
            @Nullable String aiApiKey,
            @Nullable String baseUrl,
            @Nullable String embeddingApiKey,
            @Nullable String serperKey) {

        /** {@code true} when the config deliberately configures no AI provider. */
        boolean aiConfigured() {
            return provider != null;
        }
    }

    private SetupConfigParser() {}

    static Parsed parse(Map<String, Object> yaml) {
        List<String> problems = new ArrayList<>();
        checkUnknownKeys(yaml, problems);

        Map<String, Object> tenant = section(yaml, "tenant", problems);
        Map<String, Object> user = section(yaml, "user", problems);
        Map<String, Object> ai = section(yaml, "ai", problems);

        String tenantName = requiredString(tenant, "tenant.name", problems);
        if (tenantName != null) {
            if (tenantName.isBlank()) {
                problems.add("tenant.name: must not be blank");
            } else if (TenantService.SYSTEM_TENANT.equals(tenantName)) {
                problems.add("tenant.name: '" + TenantService.SYSTEM_TENANT + "' is reserved for internal use");
            }
        }
        String tenantTitle = optionalString(tenant, "tenant.title", problems);

        String userName = requiredString(user, "user.name", problems);
        if (userName != null && !userName.isBlank() && userName.startsWith(UserService.SERVICE_ACCOUNT_PREFIX)) {
            problems.add("user.name: names starting with '" + UserService.SERVICE_ACCOUNT_PREFIX
                    + "' are reserved for service accounts");
        }
        String userTitle = optionalString(user, "user.title", problems);
        String userEmail = optionalString(user, "user.email", problems);
        String userPassword = optionalString(user, "user.password", problems);

        ProviderPreset provider = null;
        String instanceName = null;
        String aiModel = "";
        String aiApiKey = null;
        String baseUrl = null;
        String embeddingApiKey = null;
        if (ai != null) {
            String providerName = optionalString(ai, "ai.provider", problems);
            if (providerName != null && !providerName.isBlank()) {
                switch (providerName.strip().toLowerCase(Locale.ROOT)) {
                    case "gemini" -> provider = ProviderPreset.GEMINI;
                    case "openai" -> provider = ProviderPreset.OPENAI;
                    case "anthropic" -> provider = ProviderPreset.ANTHROPIC;
                    case "custom" -> provider = ProviderPreset.CUSTOM;
                    case "none" -> provider = null;
                    default ->
                        problems.add("ai.provider: unknown '" + providerName
                                + "' (gemini | openai | anthropic | custom | none)");
                }
            }
            instanceName = optionalString(ai, "ai.instance", problems);
            aiModel = Objects.requireNonNullElse(optionalString(ai, "ai.model", problems), "");
            aiApiKey = optionalString(ai, "ai.api-key", problems);
            baseUrl = optionalString(ai, "ai.base-url", problems);
            embeddingApiKey = optionalString(ai, "ai.embedding-api-key", problems);

            if (provider != null) {
                if (provider.requiresInstanceName()) {
                    String normalised = ProviderPreset.normaliseInstanceName(instanceName);
                    if (normalised == null) {
                        problems.add("ai.instance: required for provider 'custom' — lower-case letters, digits,"
                                + " '.', '_' and '-' (e.g. cortecs)");
                    } else {
                        instanceName = normalised;
                    }
                    if (baseUrl == null || baseUrl.isBlank()) {
                        problems.add("ai.base-url: required for provider 'custom' (OpenAI-compatible gateway)");
                    }
                } else {
                    if (instanceName != null) {
                        problems.add("ai.instance: only the 'custom' provider names an instance — "
                                + provider.displayName() + " carries its own");
                    }
                    if (baseUrl != null) {
                        problems.add("ai.base-url: only the 'custom' provider takes a base URL");
                    }
                }
                if (aiModel.isBlank() && !provider.requiresInstanceName()) {
                    // Same default the interactive wizard applies when picking the preset.
                    aiModel = provider.defaultModel();
                }
                if (aiModel.isBlank()) {
                    problems.add("ai.model: required when an AI provider is configured");
                }
            } else if (instanceName != null || baseUrl != null || aiApiKey != null || !aiModel.isBlank()) {
                problems.add("ai: no provider configured — set ai.provider or remove the other ai keys");
            }
        }

        String serperKey = optionalString(yaml, "serper-key", problems);

        if (!problems.isEmpty()) {
            throw new SetupConfigException(problems);
        }
        return new Parsed(
                tenantName == null ? "" : tenantName.strip(),
                tenantTitle,
                userName == null ? "" : userName.strip(),
                userTitle,
                userEmail,
                userPassword,
                provider,
                instanceName,
                aiModel,
                aiApiKey,
                baseUrl,
                embeddingApiKey,
                serperKey);
    }

    /** Returns the sub-mapping for {@code key}, or {@code null} when absent. */
    private static @Nullable Map<String, Object> section(Map<String, Object> yaml, String key, List<String> problems) {
        Object value = yaml.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        problems.add(key + ": expected a mapping of settings, got "
                + value.getClass().getSimpleName());
        return null;
    }

    private static final List<String> TOP_LEVEL_KEYS = List.of("tenant", "user", "ai", "serper-key");
    private static final List<String> TENANT_KEYS = List.of("name", "title");
    private static final List<String> USER_KEYS = List.of("name", "title", "email", "password");
    private static final List<String> AI_KEYS =
            List.of("provider", "instance", "model", "base-url", "api-key", "embedding-api-key");

    /**
     * Reads a string value under {@code section.<key>}; type mismatches land in the
     * problem list with the fully qualified key, not in a silent default.
     */
    private static @Nullable String optionalString(
            Map<String, Object> section, String dottedKey, List<String> problems) {
        String key = dottedKey.substring(dottedKey.lastIndexOf('.') + 1);
        Object value = section == null ? null : section.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String v) {
            return v;
        }
        problems.add(dottedKey + ": expected a string, got " + value.getClass().getSimpleName());
        return null;
    }

    private static @Nullable String requiredString(
            Map<String, Object> section, String dottedKey, List<String> problems) {
        String value = optionalString(section, dottedKey, problems);
        if (value == null || value.isBlank()) {
            problems.add(dottedKey + ": required");
        }
        return value;
    }

    /** Collects unknown keys in every section so the error list is complete. */
    static void checkUnknownKeys(Map<String, Object> yaml, List<String> problems) {
        for (String key : yaml.keySet()) {
            if (!TOP_LEVEL_KEYS.contains(key)) {
                problems.add(key + ": unknown setting (allowed: tenant, user, ai, serper-key)");
            }
        }
        checkSection(yaml, "tenant", TENANT_KEYS, problems);
        checkSection(yaml, "user", USER_KEYS, problems);
        checkSection(yaml, "ai", AI_KEYS, problems);
    }

    private static void checkSection(
            Map<String, Object> yaml, String name, List<String> allowed, List<String> problems) {
        Object section = yaml.get(name);
        if (!(section instanceof Map<?, ?> map)) {
            return;
        }
        for (Object key : map.keySet()) {
            if (!allowed.contains(String.valueOf(key))) {
                problems.add(name + "." + key + ": unknown setting (allowed: " + String.join(", ", allowed) + ")");
            }
        }
    }
}

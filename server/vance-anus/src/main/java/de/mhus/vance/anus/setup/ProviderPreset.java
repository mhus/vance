package de.mhus.vance.anus.setup;

/**
 * AI-provider presets the setup wizard can write. Each preset bundles the
 * defaults that {@code init-settings.yaml} would otherwise spell out:
 * {@code ai.default.provider}, {@code ai.default.model}, the
 * {@code ai.alias.default.*} block (all aliases point at the chat model
 * for v1 — the operator can split into fast/analyze/deep later in the
 * Web-UI), and the embedding provider.
 *
 * <p>Embedding strategy:
 * <ul>
 *   <li>{@link #GEMINI} / {@link #OPENAI} — embedding shares the chat
 *       provider and reuses the same API key (set via {@code ai.embedding.apiKey}).</li>
 *   <li>{@link #ANTHROPIC} — Anthropic has no embedding endpoint, so the
 *       wizard falls back to {@code ai.embedding.provider=embedded}
 *       (in-process E5-small-v2, no key).</li>
 * </ul>
 *
 * <p>{@link #CUSTOM} covers any OpenAI-compatible gateway (Cortecs, a local
 * OpenAI-compatible proxy, …): it writes the {@code openai} provider instance
 * but additionally requires a {@code baseUrl} and a model id (no sensible
 * default exists), and leaves embeddings on the keyless in-process model —
 * the custom chat endpoint may not serve embeddings. Ollama and other keyless
 * / self-hosted providers stay out-of-scope for presets; operators who need
 * them keep using {@code confidential/init-settings-ollama.yaml}.
 */
public enum ProviderPreset {

    GEMINI("gemini", "Gemini", "gemini-2.5-flash", true, false),
    OPENAI("openai", "OpenAI", "gpt-4o", true, false),
    ANTHROPIC("anthropic", "Anthropic", "claude-sonnet-4-5", false, false),
    CUSTOM("openai", "OpenAI-compatible (custom base URL)", "", false, true),
    ;

    private final String settingsId;
    private final String displayName;
    private final String defaultModel;
    private final boolean supportsEmbedding;
    private final boolean requiresBaseUrl;

    ProviderPreset(String settingsId, String displayName, String defaultModel,
            boolean supportsEmbedding, boolean requiresBaseUrl) {
        this.settingsId = settingsId;
        this.displayName = displayName;
        this.defaultModel = defaultModel;
        this.supportsEmbedding = supportsEmbedding;
        this.requiresBaseUrl = requiresBaseUrl;
    }

    /** Identifier used in setting keys ({@code ai.default.provider} value). */
    public String settingsId() {
        return settingsId;
    }

    /** Human-readable label for the wizard UI. */
    public String displayName() {
        return displayName;
    }

    /** Sensible default model for fresh setups; operator can change later. */
    public String defaultModel() {
        return defaultModel;
    }

    /**
     * Whether this provider also serves the embedding endpoint. If
     * {@code false}, the wizard sets {@code ai.embedding.provider=embedded}
     * (in-process model, no key) instead of trying to reuse the chat key.
     */
    public boolean supportsEmbedding() {
        return supportsEmbedding;
    }

    /**
     * Whether this preset needs an explicit {@code baseUrl} (an
     * OpenAI-compatible gateway with no fixed endpoint). Presets pointing at a
     * provider's own API return {@code false}.
     */
    public boolean requiresBaseUrl() {
        return requiresBaseUrl;
    }

    /**
     * Lookup by {@link #settingsId()} — used when reading defaults back. Note
     * {@link #CUSTOM} shares the {@code openai} id with {@link #OPENAI}; this
     * returns the first match ({@code OPENAI}), which is fine for pre-filling
     * the menu — the stored {@code baseUrl} setting is what actually drives the
     * runtime endpoint either way.
     */
    public static @org.jspecify.annotations.Nullable ProviderPreset fromSettingsId(String id) {
        for (ProviderPreset p : values()) {
            if (p.settingsId.equals(id)) {
                return p;
            }
        }
        return null;
    }
}

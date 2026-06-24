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
 * <p>Ollama and other keyless / self-hosted providers are deliberately
 * out-of-scope for the v1 wizard — they need extra fields ({@code baseUrl})
 * that don't fit the "API key only" preset shape. Operators who need
 * Ollama keep using {@code confidential/init-settings-ollama.yaml}.
 */
public enum ProviderPreset {

    GEMINI("gemini", "Gemini", "gemini-2.5-flash", true),
    OPENAI("openai", "OpenAI", "gpt-4o", true),
    ANTHROPIC("anthropic", "Anthropic", "claude-sonnet-4-5", false),
    ;

    private final String settingsId;
    private final String displayName;
    private final String defaultModel;
    private final boolean supportsEmbedding;

    ProviderPreset(String settingsId, String displayName, String defaultModel,
            boolean supportsEmbedding) {
        this.settingsId = settingsId;
        this.displayName = displayName;
        this.defaultModel = defaultModel;
        this.supportsEmbedding = supportsEmbedding;
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

    /** Lookup by {@link #settingsId()} — used when reading defaults back. */
    public static @org.jspecify.annotations.Nullable ProviderPreset fromSettingsId(String id) {
        for (ProviderPreset p : values()) {
            if (p.settingsId.equals(id)) {
                return p;
            }
        }
        return null;
    }
}

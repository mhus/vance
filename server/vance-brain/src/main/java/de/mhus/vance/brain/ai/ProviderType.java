package de.mhus.vance.brain.ai;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Closed set of LLM provider backends Vance speaks to. Each constant
 * carries the {@code wireName} that appears in YAML settings, recipe
 * configs, MongoDB documents and trace records — the existing
 * lower-case strings ({@code "anthropic"}, {@code "gemini"}, …)
 * are preserved verbatim so no migration is needed on the persistence
 * layer.
 *
 * <p>New backends are added by appending a constant. Resolving from
 * an arbitrary inbound string (settings cascade, REST payload) goes
 * through {@link #fromWireName(String)} which is case-insensitive but
 * fails fast on unknown values.
 */
public enum ProviderType {
    ANTHROPIC("anthropic",     true),
    OPENAI("openai",           true),
    GEMINI("gemini",           true),
    /** Local Ollama daemon — no auth required by default. */
    OLLAMA("ollama",           false),
    /** Hosted Ollama Turbo — requires a JWT. */
    OLLAMA_CLOUD("ollama-cloud", true),
    /** Local LM Studio server — no auth required by default. */
    LM_STUDIO("lmstudio",      false),
    AZURE_OPENAI("azure-openai", true),
    /** Test-only deterministic stub provider (qa/ai-test). */
    SCRIPTED("scripted",       false);

    private final String wireName;
    private final boolean requiresApiKey;

    ProviderType(String wireName, boolean requiresApiKey) {
        this.wireName = wireName;
        this.requiresApiKey = requiresApiKey;
    }

    public String wireName() {
        return wireName;
    }

    /**
     * Whether this provider demands an API-key setting. Local providers
     * (Ollama, LM Studio) speak HTTP to a localhost daemon and don't
     * authenticate; the resolver should skip the key-lookup for them
     * and pass a placeholder into {@link AiChatConfig} (whose record
     * contract still rejects blank keys).
     */
    public boolean requiresApiKey() {
        return requiresApiKey;
    }

    private static final Map<String, ProviderType> BY_WIRE = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(p -> p.wireName, p -> p));

    /** Case-insensitive lookup; {@link Optional#empty()} on unknown. */
    public static Optional<ProviderType> fromWireName(String s) {
        if (s == null) return Optional.empty();
        return Optional.ofNullable(BY_WIRE.get(s.trim().toLowerCase(Locale.ROOT)));
    }

    /** Strict variant for boot-time / config validation. */
    public static ProviderType requireWireName(String s) {
        return fromWireName(s).orElseThrow(() ->
                new IllegalArgumentException("Unknown provider wire-name: '" + s + "'"));
    }
}

package de.mhus.vance.anus.setup;

import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

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
 * <h2>{@link #CUSTOM} names its own instance</h2>
 * {@code CUSTOM} covers any OpenAI-compatible gateway (Cortecs, OpenRouter, a
 * local vLLM). It used to write the literal {@code openai} instance, and that
 * was a data-loss bug rather than a shortcut: settings are keyed
 * {@code ai.provider.<instance>.*}, so configuring a gateway through the
 * wizard overwrote the real OpenAI key <em>and</em> pointed the {@code openai}
 * instance at the gateway — silently, and with no way to hold both.
 *
 * <p>{@code CUSTOM} therefore carries <b>no</b> fixed {@code settingsId}; the
 * operator names the instance ({@code cortecs}, {@code openrouter}, …) and
 * that name becomes the settings namespace, the {@code ai.default.provider}
 * value and the left half of every model spec. Callers must go through
 * {@link #settingsIdOr(String)} rather than {@link #settingsId()} — the latter
 * throws for {@code CUSTOM} on purpose, so a call site that forgets the
 * instance fails loudly instead of falling back to a shared namespace.
 *
 * <p>Ollama and other keyless / self-hosted providers stay out-of-scope for
 * presets; operators who need them keep using
 * {@code confidential/init-settings-ollama.yaml}.
 */
public enum ProviderPreset {

    GEMINI("gemini", "Gemini", "gemini-2.5-flash", true, false),
    OPENAI("openai", "OpenAI", "gpt-4o", true, false),
    ANTHROPIC("anthropic", "Anthropic", "claude-sonnet-4-5", false, false),
    CUSTOM(null, "OpenAI-compatible gateway (own instance)", "", false, true),
    ;

    /**
     * Wire protocol written as {@code ai.provider.<instance>.type} for a
     * named instance. Every preset in this enum that needs the setting speaks
     * the OpenAI wire — a second protocol would come with its own preset.
     */
    public static final String CUSTOM_WIRE_TYPE = "openai";

    /**
     * Grammar for an instance name. Mirrors {@code ModelCatalog}'s
     * {@code PROVIDER_NAME_RE}: the name is a settings-key segment
     * <em>and</em> a directory name under {@code _vance/model/}, so a name
     * this rejects would produce settings that resolve and a model catalogue
     * that cannot. Deliberately not validated against the registered
     * {@code ProviderType} wire-names — that enum lives in {@code vance-brain},
     * which anus does not depend on, and a copy here would drift.
     */
    private static final Pattern INSTANCE_NAME_RE = Pattern.compile("[a-z0-9._-]+");

    private final @Nullable String settingsId;
    private final String displayName;
    private final String defaultModel;
    private final boolean supportsEmbedding;
    private final boolean requiresBaseUrl;

    ProviderPreset(@Nullable String settingsId, String displayName, String defaultModel,
            boolean supportsEmbedding, boolean requiresBaseUrl) {
        this.settingsId = settingsId;
        this.displayName = displayName;
        this.defaultModel = defaultModel;
        this.supportsEmbedding = supportsEmbedding;
        this.requiresBaseUrl = requiresBaseUrl;
    }

    /**
     * Fixed identifier used in setting keys ({@code ai.default.provider}
     * value).
     *
     * @throws IllegalStateException for {@link #CUSTOM}, whose instance is
     *         named by the operator — use {@link #settingsIdOr(String)}.
     */
    public String settingsId() {
        if (settingsId == null) {
            throw new IllegalStateException(
                    "Preset " + name() + " has no fixed settings id — the operator names "
                            + "the instance; call settingsIdOr(instanceName)");
        }
        return settingsId;
    }

    /**
     * The settings namespace to write under: the preset's own id, or
     * {@code instanceName} for presets that name their instance.
     *
     * @throws IllegalArgumentException when this preset needs an instance name
     *         and none was supplied.
     */
    public String settingsIdOr(@Nullable String instanceName) {
        if (settingsId != null) {
            return settingsId;
        }
        String normalised = normaliseInstanceName(instanceName);
        if (normalised == null) {
            throw new IllegalArgumentException(
                    "Preset " + name() + " requires an instance name");
        }
        return normalised;
    }

    /** Whether the operator has to name the provider instance. */
    public boolean requiresInstanceName() {
        return settingsId == null;
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
     * Trims and lower-cases a typed instance name, or returns {@code null}
     * when it is blank or violates {@link #INSTANCE_NAME_RE}. Lower-casing
     * rather than rejecting mixed case: the name is echoed straight back into
     * a settings key, and "Cortecs" silently becoming a second namespace next
     * to "cortecs" is the failure this whole change exists to remove.
     */
    public static @Nullable String normaliseInstanceName(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty() || !INSTANCE_NAME_RE.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed;
    }

    /**
     * Lookup by a stored {@code ai.default.provider} value. Returns
     * {@code null} for anything that is not a fixed-id preset — including
     * every operator-named instance, which the wizard reads back as
     * {@link #CUSTOM} plus the name itself.
     */
    public static @Nullable ProviderPreset fromSettingsId(String id) {
        for (ProviderPreset p : values()) {
            if (id.equals(p.settingsId)) {
                return p;
            }
        }
        return null;
    }
}

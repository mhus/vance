package de.mhus.vance.brain.ai.light;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A schema-validated reply together with the identity of the model that
 * produced it.
 *
 * <p>The model is not derivable from the recipe by a consumer. Its
 * {@code params.model} is an alias, resolved through the setting cascade
 * against a catalog that changes without the recipe changing — so the
 * recipe names an intent, and only the service that built the call can
 * say what that intent resolved to.
 *
 * <p>{@code model} is qualified as {@code <providerInstance>:<modelName>},
 * the same form {@code modelAlias} takes in the usage ledger, so a record
 * written by a consumer can be lined up against the cost of the call.
 *
 * <p>{@code usage} carries the token counts of the <em>answering</em>
 * attempt. Callers that meter their own budget (an engine that runs many
 * light calls per run, e.g. Benjy's controller loop) need the numbers at
 * the call site — the audit trail records them, but audit is a log
 * stream, not a return channel. {@code null} when the provider did not
 * report usage; consumers must treat that as "unknown", never zero.
 *
 * @param json  the parsed reply object — never {@code null}
 * @param model the model that <em>answered</em>, which after a retry
 *              onto a fallback is not the one the call started with.
 *              Never {@code null} on this path today, but a consumer
 *              must still handle it as unknown rather than substituting
 *              the model it assumed — an older producer may not send it
 */
public record LightLlmJsonAnswer(
        Map<String, Object> json,
        @Nullable String model,
        @Nullable Usage usage) {

    /**
     * Token counts of the answering attempt. Both fields are
     * {@code null} when the provider did not report them.
     */
    public record Usage(
            @Nullable Integer inputTokens, @Nullable Integer outputTokens) {

        static @Nullable Usage of(@Nullable Integer in, @Nullable Integer out) {
            if (in == null && out == null) return null;
            return new Usage(in, out);
        }
    }

    /** Backward-compatible shape — no usage reported. */
    public LightLlmJsonAnswer(Map<String, Object> json, @Nullable String model) {
        this(json, model, null);
    }
}

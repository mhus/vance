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
 * @param json  the parsed reply object — never {@code null}
 * @param model never {@code null} on this path today, but a consumer
 *              must still handle it as unknown rather than substituting
 *              the model it assumed — an older producer may not send it
 */
public record LightLlmJsonAnswer(Map<String, Object> json, @Nullable String model) {}

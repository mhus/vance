package de.mhus.vance.brain.ai;

import de.mhus.vance.brain.ai.parser.MessageParser;
import de.mhus.vance.brain.ai.parser.MessageParserRegistry;
import de.mhus.vance.shared.llmusage.CallAttribution;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Template-method base for {@link AiModelProvider} implementations.
 *
 * <p>Concentrates the cross-cutting orchestration each backend has to
 * do — provider-name validation, {@link ModelCatalog} lookup, option
 * gates, request-shape workarounds ({@link SystemMessageMerger}),
 * {@link StandardAiChat} construction (including the
 * {@link LlmResponseSanitizer} decorator wiring), and the uniform
 * {@link AiChatException} wrap on failure — so new cross-cutting
 * concerns (caching policies, trace layers, decorators) land in one
 * place instead of being copy-pasted across every provider.
 *
 * <p>Subclasses supply only what is genuinely backend-specific:
 *
 * <ul>
 *   <li>{@link #getType()} — already mandated by {@link AiModelProvider}.</li>
 *   <li>{@link #buildModels} — construct the langchain4j sync +
 *       streaming {@link ChatModel}/{@link StreamingChatModel} for
 *       this backend (Anthropic-direct client, Gemini chat builder,
 *       OpenAI builder with reasoning effort, Ollama builder with
 *       {@code numCtx}, etc.). Receives the already-gated effective
 *       {@link AiChatOptions} plus the resolved {@link ModelInfo}.</li>
 *   <li>{@link #applyOptionGates} — optional hook for provider-
 *       specific option transformation (e.g. Anthropic's
 *       capability/cache kill). Default is identity.</li>
 * </ul>
 *
 * <p>Subclasses remain {@code @Component} Spring beans with their own
 * {@code @Value}-injected per-provider config (base URL, cache flags,
 * etc.); those don't belong in the template.
 */
public abstract class AbstractChatProvider implements AiModelProvider {

    private static final Logger TEMPLATE_LOG =
            LoggerFactory.getLogger(AbstractChatProvider.class);

    protected final ModelCatalog modelCatalog;
    protected final LlmResponseSanitizer responseSanitizer;
    protected final MessageParserRegistry messageParserRegistry;
    protected final UsageSink usageSink;

    protected AbstractChatProvider(
            ModelCatalog modelCatalog,
            LlmResponseSanitizer responseSanitizer,
            MessageParserRegistry messageParserRegistry,
            UsageSink usageSink) {
        this.modelCatalog = modelCatalog;
        this.responseSanitizer = responseSanitizer;
        this.messageParserRegistry = messageParserRegistry;
        this.usageSink = usageSink;
    }

    /**
     * Final template method — same shape for every backend. If a
     * subclass needs to deviate (none does at the moment), it can
     * override at its own risk; final preserves the orchestration
     * contract so we can evolve cross-cutting layers (e.g. add another
     * decorator) in one place.
     *
     * <p>This is where usage accounting is attached, and the only place:
     * {@code attribution} says who pays, {@code modelInfo} carries the rate
     * snapshot, and both are in scope here. Wrapping the built pair before
     * it reaches {@link StandardAiChat} puts the accounting decorator
     * innermost — below the trace log, below the retry layer — so it sees
     * every attempt on the wire exactly once.
     */
    @Override
    public final AiChat createChat(
            AiChatConfig config, AiChatOptions options, CallAttribution attribution) {
        String wireName = getType().wireName();
        if (!wireName.equals(config.provider())) {
            throw new AiChatException(
                    getClass().getSimpleName()
                            + " received config for provider '" + config.provider() + "'");
        }
        ModelInfo modelInfo = modelCatalog.lookupOrDefault(
                options.getTenantId(), options.getProjectId(),
                config.providerInstance(), wireName, config.modelName());
        AiChatOptions gated = applyOptionGates(options, modelInfo);
        // Ensure an output-token cap is always present. When neither the
        // caller nor the recipe set maxTokens, fall back to the model's
        // catalog default; otherwise OpenAI-wire gateways reserve the
        // entire remaining context window as output and overflow the
        // context limit on large prompts.
        AiChatOptions effective = gated.getMaxTokens() != null
                ? gated
                : gated.toBuilder()
                        .maxTokens(modelInfo.effectiveMaxOutputTokens(null))
                        .build();
        effective = stripUnsupportedParams(effective, modelInfo);
        MessageParser parser = messageParserRegistry
                .get(modelInfo.messageParser())
                .orElse(null);
        if (parser == null && modelInfo.messageParser() != null) {
            TEMPLATE_LOG.warn("Model '{}': messageParser='{}' has no registered bean — "
                            + "passing responses through unchanged",
                    modelInfo.modelName(), modelInfo.messageParser());
        }
        try {
            BuiltChat built = withAccounting(
                    mergeSystemMessages(buildModels(config, effective, modelInfo), modelInfo),
                    attribution, modelInfo, config.providerInstance(), usageSink);
            return new StandardAiChat(
                    config.fullName(),
                    getType(),
                    modelInfo.capabilities(),
                    built.sync(),
                    built.streaming(),
                    effective,
                    modelInfo.stripThinkTags(),
                    responseSanitizer,
                    parser);
        } catch (AiChatException e) {
            // Subclass already produced a typed message — pass through
            // verbatim so call sites see the precise failure cause.
            throw e;
        } catch (RuntimeException e) {
            TEMPLATE_LOG.debug("Provider '{}' failed for '{}': {}",
                    wireName, config.fullName(), e.toString());
            throw new AiChatException(
                    "Failed to build " + wireName + " chat for " + config.fullName(), e);
        }
    }

    /**
     * Wrap the built pair so every attempt is booked into the usage ledger.
     *
     * <p>Unconditional by design. The trace layer next door is opt-in and
     * caller-wired, and that is precisely how four call sites ended up
     * issuing unaccounted calls. Accounting has no switch and no null
     * branch: {@link UsageSink#NOOP} exists for construction paths outside
     * Spring, not as a way to turn billing off.
     *
     * @see UsageAccountingChatModel
     */
    static BuiltChat withAccounting(
            BuiltChat built,
            CallAttribution attribution,
            ModelInfo modelInfo,
            String providerInstance,
            UsageSink sink) {
        return new BuiltChat(
                new UsageAccountingChatModel(
                        built.sync(), attribution, modelInfo, providerInstance, sink),
                built.streaming() == null
                        ? null
                        : new UsageAccountingStreamingChatModel(
                                built.streaming(), attribution, modelInfo,
                                providerInstance, sink));
    }

    /**
     * Wraps the built pair so consecutive system messages are collapsed
     * before they go on the wire — for models whose catalog entry asks
     * for it ({@code mergeSystemMessages}).
     *
     * <p>Deliberately in the template, not in one provider: the flag is
     * a fact about the endpoint that renders the request, and the same
     * model arrives through more than one backend. Ollama's
     * {@code glimmer} renderer — the reason the flag exists — serves the
     * same models through the local Ollama API, through Ollama Cloud, and
     * through any OpenAI-compatible gateway in front of it. A workaround
     * wired into a single provider would silently not apply to the other
     * two, and the symptom (a context overflow) gives no hint why.
     *
     * @see SystemMessageMerger
     */
    static BuiltChat mergeSystemMessages(BuiltChat built, ModelInfo modelInfo) {
        if (!modelInfo.mergeSystemMessages()) {
            return built;
        }
        return new BuiltChat(
                new SystemMessageMergingChatModel(built.sync()),
                built.streaming() == null
                        ? null
                        : new SystemMessageMergingStreamingChatModel(built.streaming()));
    }

    /**
     * Clear the sampling knobs the resolved model refuses, so the model
     * runs on its own decoding defaults instead of rejecting the whole
     * request. Reasoning models (OpenAI o-series, gpt-5+) answer a
     * stray {@code temperature} or {@code stop} with a hard HTTP 400 —
     * and {@link AiChatOptions} carries a {@code temperature} default,
     * so without this every turn against such a model would die.
     *
     * <p>Deliberately in the template, not in a single provider: the
     * field is a fact about the model, and the same model can arrive
     * through more than one backend.
     */
    static AiChatOptions stripUnsupportedParams(
            AiChatOptions options, ModelInfo modelInfo) {
        Set<SamplingParam> unsupported = modelInfo.unsupportedParams();
        if (unsupported.isEmpty()) {
            return options;
        }
        AiChatOptions.AiChatOptionsBuilder builder = options.toBuilder();
        List<String> dropped = new ArrayList<>(unsupported.size());
        for (SamplingParam param : unsupported) {
            boolean wasSet = switch (param) {
                case TEMPERATURE -> clear(options.getTemperature(), builder::temperature);
                case TOP_P -> clear(options.getTopP(), builder::topP);
                case TOP_K -> clear(options.getTopK(), builder::topK);
                case FREQUENCY_PENALTY ->
                        clear(options.getFrequencyPenalty(), builder::frequencyPenalty);
                case PRESENCE_PENALTY ->
                        clear(options.getPresencePenalty(), builder::presencePenalty);
                case SEED -> clear(options.getSeed(), builder::seed);
                case STOP_SEQUENCES -> {
                    List<String> stops = options.getStopSequences();
                    boolean present = stops != null && !stops.isEmpty();
                    builder.stopSequences(null);
                    yield present;
                }
            };
            if (wasSet) {
                dropped.add(param.wireName());
            }
        }
        if (!dropped.isEmpty()) {
            TEMPLATE_LOG.debug("Model '{}/{}' does not accept {} — dropped from this call",
                    modelInfo.provider(), modelInfo.modelName(), dropped);
        }
        return builder.build();
    }

    /** Null out one option field; reports whether it carried a value. */
    private static <T> boolean clear(@Nullable T current, Consumer<@Nullable T> setter) {
        setter.accept(null);
        return current != null;
    }

    /**
     * Provider-specific construction of the langchain4j sync +
     * streaming chat models. Called inside the template's try/catch,
     * so subclasses can throw {@link RuntimeException} and the
     * template wraps it into {@link AiChatException}.
     */
    protected abstract BuiltChat buildModels(
            AiChatConfig config,
            AiChatOptions effective,
            ModelInfo modelInfo);

    /**
     * Optional hook for provider-specific option transformation
     * (capability gates, cache-kill, etc.). Default is identity.
     * Subclass overrides must return a value safe to pass into both
     * {@link #buildModels} and {@link StandardAiChat} — typically a
     * fresh {@link AiChatOptions} clone with the relevant fields
     * adjusted.
     */
    protected AiChatOptions applyOptionGates(AiChatOptions options, ModelInfo modelInfo) {
        return options;
    }

    /**
     * Bundle returned from {@link #buildModels}. {@code streaming}
     * may be {@code null} when a backend exposes only a sync model;
     * {@link StandardAiChat} handles that case.
     */
    public record BuiltChat(ChatModel sync, @Nullable StreamingChatModel streaming) {}
}

package de.mhus.vance.brain.ai;

import de.mhus.vance.brain.ai.parser.MessageParser;
import de.mhus.vance.brain.ai.parser.MessageParserRegistry;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Template-method base for {@link AiModelProvider} implementations.
 *
 * <p>Concentrates the cross-cutting orchestration each backend has to
 * do — provider-name validation, {@link ModelCatalog} lookup, option
 * gates, {@link StandardAiChat} construction (including the
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

    protected AbstractChatProvider(
            ModelCatalog modelCatalog,
            LlmResponseSanitizer responseSanitizer,
            MessageParserRegistry messageParserRegistry) {
        this.modelCatalog = modelCatalog;
        this.responseSanitizer = responseSanitizer;
        this.messageParserRegistry = messageParserRegistry;
    }

    /**
     * Final template method — same shape for every backend. If a
     * subclass needs to deviate (none does at the moment), it can
     * override at its own risk; final preserves the orchestration
     * contract so we can evolve cross-cutting layers (e.g. add another
     * decorator) in one place.
     */
    @Override
    public final AiChat createChat(AiChatConfig config, AiChatOptions options) {
        String wireName = getType().wireName();
        if (!wireName.equals(config.provider())) {
            throw new AiChatException(
                    getClass().getSimpleName()
                            + " received config for provider '" + config.provider() + "'");
        }
        ModelInfo modelInfo = modelCatalog.lookupOrDefault(
                options.getTenantId(), options.getProjectId(),
                config.providerInstance(), wireName, config.modelName());
        AiChatOptions effective = applyOptionGates(options, modelInfo);
        MessageParser parser = messageParserRegistry
                .get(modelInfo.messageParser())
                .orElse(null);
        if (parser == null && modelInfo.messageParser() != null) {
            TEMPLATE_LOG.warn("Model '{}': messageParser='{}' has no registered bean — "
                            + "passing responses through unchanged",
                    modelInfo.modelName(), modelInfo.messageParser());
        }
        try {
            BuiltChat built = buildModels(config, effective, modelInfo);
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

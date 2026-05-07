package de.mhus.vance.brain.ai;

/**
 * A provider plug-in for {@link AiModelService}. One Spring bean per backend
 * (Anthropic, Gemini, OpenAI, ...). {@link AiModelService} auto-discovers
 * all beans of this type.
 *
 * <p>Providers are stateless w.r.t. a given call — each
 * {@link #createChat(AiChatConfig, AiChatOptions)} invocation builds a fresh
 * {@link AiChat}. Any per-provider caching / rate limiting stays inside the
 * provider implementation.
 */
public interface AiModelProvider {

    /**
     * Typed identity of the backend this provider speaks to. Drives the
     * dispatch map in {@link AiModelService} and replaces the loose
     * {@link #getName()} string for new call sites.
     */
    ProviderType getType();

    /**
     * Registered provider name, lowercase. Defaults to
     * {@link ProviderType#wireName()} so new providers don't have to
     * duplicate the constant. Used in {@link AiChatConfig#provider()}.
     */
    default String getName() {
        return getType().wireName();
    }

    /**
     * Build a chat bound to {@code config} with the given runtime
     * {@code options}.
     *
     * @throws AiChatException if instantiation fails
     */
    AiChat createChat(AiChatConfig config, AiChatOptions options);
}

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
     * Registered provider name, lowercase. Used in {@link AiChatConfig#provider()}
     * and in the dispatch map of {@link AiModelService}.
     */
    String getName();

    /**
     * Build a chat bound to {@code config} with the given runtime
     * {@code options}.
     *
     * @throws AiChatException if instantiation fails
     */
    AiChat createChat(AiChatConfig config, AiChatOptions options);
}

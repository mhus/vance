package de.mhus.vance.brain.ai;

import de.mhus.vance.brain.ai.attachment.AttachmentException;
import de.mhus.vance.brain.ai.attachment.PdfTextExtractor;
import de.mhus.vance.brain.ai.attachment.ResolvedAttachment;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Provider-agnostic {@link AiChat} wrapper. Holds a matched sync/streaming
 * model pair produced by any {@link AiModelProvider} implementation.
 *
 * <p>Everything in here talks to langchain4j's generic
 * {@link ChatModel} / {@link StreamingChatModel} interfaces, so this class
 * works unchanged for Anthropic, Gemini, OpenAI, etc. Provider-specific
 * wiring (api-key format, model-builder quirks) stays in the provider bean.
 */
@Slf4j
public class StandardAiChat implements AiChat {

    /**
     * Providers whose langchain4j adapter (or our own direct adapter)
     * actually consumes a {@link PdfFileContent} block. For everything
     * else we fall back to PDFBox text extraction. Anthropic uses our
     * own {@code AnthropicRequestMapper}, which translates
     * {@code PdfFileContent} into the native {@code document} block.
     */
    private static final Set<ProviderType> NATIVE_PDF_PROVIDERS =
            EnumSet.of(ProviderType.ANTHROPIC, ProviderType.GEMINI);

    private final String name;
    private final ProviderType providerType;
    private final Set<ModelCapability> modelCapabilities;
    private final ChatModel sync;
    private final StreamingChatModel streaming;
    private final AiChatOptions options;

    public StandardAiChat(
            String name,
            ProviderType providerType,
            ChatModel sync,
            StreamingChatModel streaming,
            AiChatOptions options) {
        this(name, providerType, Set.of(), sync, streaming, options, false, null);
    }

    public StandardAiChat(
            String name,
            ProviderType providerType,
            Set<ModelCapability> modelCapabilities,
            ChatModel sync,
            StreamingChatModel streaming,
            AiChatOptions options) {
        this(name, providerType, modelCapabilities, sync, streaming, options, false, null);
    }

    /**
     * Full constructor — providers pass the resolved
     * {@link ModelInfo#stripThinkTags()} flag and the
     * {@link LlmResponseSanitizer} bean. Both are inert when
     * {@code stripThinkTags=false} so the existing zero-config
     * call sites keep behaving exactly as before.
     */
    public StandardAiChat(
            String name,
            ProviderType providerType,
            Set<ModelCapability> modelCapabilities,
            ChatModel sync,
            StreamingChatModel streaming,
            AiChatOptions options,
            boolean stripThinkTags,
            @Nullable LlmResponseSanitizer sanitizer) {
        this.name = name;
        this.providerType = providerType;
        this.modelCapabilities = modelCapabilities == null
                ? Set.of()
                : Set.copyOf(modelCapabilities);
        // Decorator stack — innermost first:
        //   provider ChatModel  (raw)
        //     ↑
        //   LoggingChatModel    (trace + stats see raw)
        //     ↑
        //   SanitizingChatModel (engines see cleaned; ONLY when the
        //                       model carries stripThinkTags=true)
        //
        // The sanitizer wrap is skipped when the flag is false so
        // straightforward (non-reasoning) models keep the old
        // call-graph exactly.
        this.sync = sync == null
                ? null
                : maybeSanitize(
                        new LoggingChatModel(
                                name, sync,
                                options.getLlmTraceWriter(),
                                options.getMetricService()),
                        sanitizer, stripThinkTags);
        this.streaming = wrapStreaming(name, streaming, options);
        this.options = options;
    }

    /**
     * Wraps {@code chat} with {@link SanitizingChatModel} when the
     * active model is marked {@code stripThinkTags} AND a sanitizer
     * bean is available. Otherwise returns the input unchanged —
     * the decorator vanishes from the call-graph.
     */
    private static ChatModel maybeSanitize(
            ChatModel chat,
            @Nullable LlmResponseSanitizer sanitizer,
            boolean enabled) {
        if (!enabled || sanitizer == null) return chat;
        return new SanitizingChatModel(chat, sanitizer, true);
    }

    /**
     * Stack the streaming model: trace-logging inside, retry / chain-fallback
     * outside. The resilient layer hides transient provider failures (rate
     * limits, demand spikes, 5xx) from every engine — they see one
     * {@link StreamingChatModel} that just works, or a clean
     * {@link AiChatException} when everything is genuinely down.
     *
     * <p>When {@code options.userNotifier} is set, the resilient layer also
     * pushes human-readable retry / chain-advance messages so the engine
     * call-site can surface them in the user-progress side-channel.
     */
    private static @Nullable StreamingChatModel wrapStreaming(
            String name, @Nullable StreamingChatModel raw, AiChatOptions options) {
        if (raw == null) {
            return null;
        }
        StreamingChatModel logged = new LoggingStreamingChatModel(
                name, raw, options.getLlmTraceWriter(), options.getMetricService());
        return new ResilientStreamingChatModel(
                List.of(new ChainEntry(logged, name, RetryPolicy.DEFAULT)),
                options.getUserNotifier());
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ProviderType providerType() {
        return providerType;
    }

    @Override
    public String ask(String question, List<ResolvedAttachment> attachments) {
        if (question == null || question.isBlank()) {
            throw new AiChatException("question is blank");
        }
        try {
            ChatResponse response = sync.chat(buildRequest(question, attachments));
            return response.aiMessage().text();
        } catch (RuntimeException e) {
            throw new AiChatException(
                    "Sync call failed for '" + name + "': " + e.getMessage(), e);
        }
    }

    @Override
    public String askStream(
            String question,
            Consumer<String> tokenConsumer,
            List<ResolvedAttachment> attachments) {
        if (question == null || question.isBlank()) {
            throw new AiChatException("question is blank");
        }
        if (tokenConsumer == null) {
            throw new AiChatException("tokenConsumer is null");
        }

        ChatRequest request = buildRequest(question, attachments);
        StringBuilder accumulator = new StringBuilder();
        CompletableFuture<String> result = new CompletableFuture<>();

        StreamingChatResponseHandler handler = new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                if (partial == null || partial.isEmpty()) {
                    return;
                }
                accumulator.append(partial);
                try {
                    tokenConsumer.accept(partial);
                } catch (RuntimeException e) {
                    log.warn("tokenConsumer threw for chat '{}': {}", name, e.toString());
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse complete) {
                result.complete(accumulator.toString());
            }

            @Override
            public void onError(Throwable error) {
                result.completeExceptionally(error);
            }
        };

        try {
            streaming.chat(request, handler);
            return result.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new AiChatException(
                    "Stream failed for '" + name + "': " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiChatException("Stream interrupted for '" + name + "'", e);
        } catch (RuntimeException e) {
            throw new AiChatException(
                    "Stream failed for '" + name + "': " + e.getMessage(), e);
        }
    }

    @Override
    public ChatModel chatModel() {
        return sync;
    }

    @Override
    public StreamingChatModel streamingChatModel() {
        return streaming;
    }

    @Override
    public boolean isAvailable() {
        return sync != null && streaming != null;
    }

    private ChatRequest buildRequest(
            String question, @Nullable List<ResolvedAttachment> attachments) {
        List<ChatMessage> messages = new ArrayList<>();
        String system = options.getSystemMessage();
        if (system != null && !system.isBlank()) {
            messages.add(SystemMessage.from(system));
        }
        messages.add(buildUserMessage(question, attachments));
        return ChatRequest.builder().messages(messages).build();
    }

    /**
     * Build the {@link UserMessage} either as a plain text message
     * (no attachments — the cheap path that all langchain4j adapters
     * understand) or as a multimodal one with content blocks.
     *
     * <p>Attachments are placed <i>before</i> the question text: they
     * are typically static (a PDF the user uploaded once), the question
     * is the dynamic part. Caching layers (Anthropic's
     * {@code cache_control}, OpenAI's prefix-cache, Gemini's implicit
     * cache) reward this ordering — the static prefix stays stable
     * across turns.
     */
    private UserMessage buildUserMessage(
            String question, @Nullable List<ResolvedAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return UserMessage.from(question);
        }
        List<Content> blocks = new ArrayList<>();
        for (ResolvedAttachment att : attachments) {
            blocks.add(toContentBlock(att, name, providerType, modelCapabilities));
        }
        blocks.add(TextContent.from(question));
        return UserMessage.from(blocks);
    }

    /**
     * Map a single resolved attachment to a langchain4j {@link Content}
     * block. Provider/capability-aware:
     *
     * <ul>
     *   <li>Image → {@link ImageContent} when the model has
     *       {@link ModelCapability#VISION}; otherwise hard fail (sending
     *       a non-vision model an image is wasted tokens at best).</li>
     *   <li>PDF → {@link PdfFileContent} for native-PDF providers
     *       (Anthropic via our own request mapper, Gemini via langchain4j)
     *       <i>and</i> when the model carries {@link ModelCapability#PDF};
     *       otherwise PDFBox text extraction lands as
     *       {@link TextContent}.</li>
     *   <li>Text-ish (markdown, JSON, …) → {@link TextContent} verbatim,
     *       with a brief filename prefix so the model can refer back.</li>
     * </ul>
     *
     * <p>Public + static so engines (Arthur, Marvin, …) can pre-bake
     * a multimodal {@link UserMessage} for the inbound user turn before
     * handing off to the streaming model loop, and unit tests can pin
     * the dispatch matrix without standing up a real chat model.
     */
    public static Content toContentBlock(
            ResolvedAttachment att,
            String chatName,
            ProviderType providerType,
            Set<ModelCapability> modelCapabilities) {
        if (att.isImage()) {
            if (!modelCapabilities.contains(ModelCapability.VISION)) {
                throw new AttachmentException(
                        "Model '" + chatName + "' has no VISION capability — cannot send image '"
                                + att.originalFilename() + "'");
            }
            String base64 = Base64.getEncoder().encodeToString(att.data());
            return ImageContent.from(base64, att.mimeType());
        }
        if (att.isPdf()) {
            boolean nativePdf = NATIVE_PDF_PROVIDERS.contains(providerType)
                    && modelCapabilities.contains(ModelCapability.PDF);
            if (nativePdf) {
                String base64 = Base64.getEncoder().encodeToString(att.data());
                return PdfFileContent.from(base64, att.mimeType());
            }
            // Fallback: PDFBox text extract rides as a TextContent block.
            log.debug("PDF fallback: extracting text for attachment '{}' "
                            + "(provider={}, modelCaps={})",
                    att.originalFilename(), providerType, modelCapabilities);
            String text = PdfTextExtractor.extract(att.data());
            return TextContent.from(
                    "[Attachment: " + att.originalFilename() + " — PDF text extract]\n" + text);
        }
        if (att.isText()) {
            String text = new String(att.data(), StandardCharsets.UTF_8);
            return TextContent.from(
                    "[Attachment: " + att.originalFilename() + "]\n" + text);
        }
        throw new AttachmentException(
                "Unsupported attachment MIME for content block: '" + att.mimeType()
                        + "' (filename=" + att.originalFilename() + ")");
    }
}

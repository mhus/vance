package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import de.mhus.vance.shared.llmusage.CallAttribution;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The cache normalization against the <em>real</em> OpenAI client and a real HTTP
 * answer, not against hand-built response objects.
 *
 * <p>The decorator tests elsewhere use plain {@code ChatResponse} objects, and
 * they pass. The real client puts its answer into a provider specific metadata
 * class that narrows the usage to {@code OpenAiTokenUsage}; rebuilding the
 * response through that class coerced the normalized usage back, dropping the
 * cache counters. The ledger then booked a cached call as {@code cacheRead = 0}
 * (and invented an "implicit" cache), for every OpenAI-wire provider.
 */
class CacheAwareUsageOpenAiWireTest {

    /** 4021 prompt tokens, 3968 of them from the provider's cache; what Cortecs and OpenRouter answer. */
    private static final String USAGE =
            "\"usage\":{\"prompt_tokens\":4021,\"completion_tokens\":3,\"total_tokens\":4024,"
                    + "\"prompt_tokens_details\":{\"cached_tokens\":3968}}";

    private static final String STREAM = "data: {\"id\":\"gen-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
            + "\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}\n\n"
            + "data: {\"id\":\"gen-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\","
            + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: {\"id\":\"gen-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\","
            + "\"choices\":[]," + USAGE + "}\n\n"
            + "data: [DONE]\n\n";

    private static final String SYNC = "{\"id\":\"gen-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\","
            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
            + "\"finish_reason\":\"stop\"}]," + USAGE + "}";

    private HttpServer server;
    private UsageMeasurement booked;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean stream = request.replaceAll("\\s", "").contains("\"stream\":true");
            byte[] body = (stream ? STREAM : SYNC).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static ModelInfo model() {
        return new ModelInfo(
                "openai",
                "m",
                200_000,
                8192,
                ModelSize.LARGE,
                Set.<ModelCapability>of(),
                60,
                2,
                false,
                null,
                new ModelInfo.Pricing("EUR", 0.06, 0.25, 0.015, null),
                OutputTokenParam.MAX_TOKENS,
                Set.<SamplingParam>of(),
                null,
                null,
                false);
    }

    private UsageSink sink() {
        return (who, what) -> booked = what;
    }

    private static CallAttribution who() {
        return new CallAttribution("t", "p", null, null, "test", null);
    }

    /** A request far bigger than the 53 uncached tokens: the situation that trips the estimator. */
    private static ChatRequest bigRequest() {
        return ChatRequest.builder()
                .messages(UserMessage.from("word ".repeat(20_000)))
                .build();
    }

    @Test
    void aCachedStreamIsBookedAsAReportedCacheReadNotAsAnImplicitOne() throws Exception {
        var stack = new UsageAccountingStreamingChatModel(
                new CacheAwareUsageStreamingChatModel(OpenAiStreamingChatModel.builder()
                        .baseUrl(baseUrl())
                        .apiKey("k")
                        .modelName("m")
                        .build()),
                who(),
                model(),
                "openai",
                sink());
        CompletableFuture<ChatResponse> done = new CompletableFuture<>();

        stack.chat(bigRequest(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {}

            @Override
            public void onCompleteResponse(ChatResponse response) {
                done.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                done.completeExceptionally(error);
            }
        });
        ChatResponse response = done.get();

        assertThat(booked.tokensIn()).isEqualTo(53);
        assertThat(booked.cacheReadTokens()).isEqualTo(3968);
        assertThat(booked.implicitCacheReadTokens()).isZero();
        // What the caller reads is intact as well.
        assertThat(response.metadata().id()).isEqualTo("gen-1");
        assertThat(response.metadata().modelName()).isEqualTo("m");
        assertThat(response.metadata().finishReason()).isNotNull();
        assertThat(response.aiMessage().text()).isEqualTo("ok");
    }

    @Test
    void aCachedPlainResponseIsBookedTheSameWay() {
        var stack = new UsageAccountingChatModel(
                new CacheAwareUsageChatModel(OpenAiChatModel.builder()
                        .baseUrl(baseUrl())
                        .apiKey("k")
                        .modelName("m")
                        .build()),
                who(),
                model(),
                "openai",
                sink());

        ChatResponse response = stack.chat(bigRequest());

        assertThat(booked.tokensIn()).isEqualTo(53);
        assertThat(booked.cacheReadTokens()).isEqualTo(3968);
        assertThat(booked.implicitCacheReadTokens()).isZero();
        assertThat(response.metadata().id()).isEqualTo("gen-1");
        assertThat(response.aiMessage().text()).isEqualTo("ok");
    }
}

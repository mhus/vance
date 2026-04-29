package de.mhus.vance.aitest;

import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelProvider;
import de.mhus.vance.brain.ai.StandardAiChat;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Test-only {@link AiModelProvider} that throws on demand. Used by
 * {@code ResilientChatStressTest} to drive the resilient layer through
 * its retry / chain-fallback paths without depending on a real provider's
 * willingness to misbehave.
 *
 * <p>Provider name is {@code failing}. Behaviour is encoded in the
 * {@code modelName} of the {@link AiChatConfig}:
 *
 * <ul>
 *   <li>{@code always-503} — every call throws "HTTP 503: high demand"</li>
 *   <li>{@code always-overloaded} — every call throws "model overloaded"</li>
 *   <li>{@code N-then-ok} (e.g. {@code 3-then-ok}) — first {@code N} calls
 *       throw retriable errors, then completes successfully</li>
 *   <li>{@code non-retriable} — throws "INVALID_ARGUMENT" (not in the
 *       default retry-pattern set)</li>
 *   <li>{@code always-ok} — always completes successfully (used as fallback
 *       target in chain tests)</li>
 * </ul>
 *
 * <p>Counters are <i>per modelName</i>, shared across all instances built
 * during one JVM — that lets a test that creates multiple chats with the
 * same {@code modelName} observe the cumulative call count.
 */
@Component
public class FailingAiProvider implements AiModelProvider {

    public static final String PROVIDER_NAME = "failing";

    private static final Map<String, AtomicInteger> COUNTERS = new HashMap<>();

    /** Resets all per-modelName counters. Call from {@code @BeforeEach}. */
    public static synchronized void resetCounters() {
        COUNTERS.values().forEach(c -> c.set(0));
    }

    /** Number of {@code chat()} invocations against the given model. */
    public static synchronized int callCount(String modelName) {
        AtomicInteger c = COUNTERS.get(modelName.toLowerCase(Locale.ROOT));
        return c == null ? 0 : c.get();
    }

    private static synchronized AtomicInteger counter(String modelName) {
        return COUNTERS.computeIfAbsent(
                modelName.toLowerCase(Locale.ROOT),
                k -> new AtomicInteger(0));
    }

    @Override
    public String getName() {
        return PROVIDER_NAME;
    }

    @Override
    public AiChat createChat(AiChatConfig config, AiChatOptions options) {
        FailureMode mode = FailureMode.parse(config.modelName());
        AtomicInteger callCount = counter(config.modelName());
        StreamingChatModel streaming =
                new SyntheticStreamingModel(mode, callCount);
        ChatModel sync = null;  // sync side not needed for the resilient-layer tests
        String label = PROVIDER_NAME + ":" + config.modelName();
        return new StandardAiChat(label, sync, streaming, options);
    }

    /** Behaviour spec parsed from the {@code modelName}. */
    private sealed interface FailureMode {
        boolean shouldFailOnCall(int oneIndexedCall);
        RuntimeException buildException();

        static FailureMode parse(String modelName) {
            String mn = modelName == null ? "" : modelName.trim().toLowerCase(Locale.ROOT);
            return switch (mn) {
                case "always-503" -> new AlwaysRetriable("HTTP error (503): high demand");
                case "always-overloaded" -> new AlwaysRetriable("HTTP error (503): model overloaded");
                case "non-retriable" -> new AlwaysNonRetriable();
                case "always-ok" -> new AlwaysOk();
                default -> {
                    if (mn.endsWith("-then-ok")) {
                        try {
                            int n = Integer.parseInt(mn.substring(0, mn.length() - "-then-ok".length()));
                            yield new NThenOk(n);
                        } catch (NumberFormatException ignored) {
                            // fall through
                        }
                    }
                    throw new IllegalArgumentException(
                            "Unknown failing model spec: '" + modelName + "'");
                }
            };
        }
    }

    private record AlwaysRetriable(String message) implements FailureMode {
        @Override public boolean shouldFailOnCall(int n) { return true; }
        @Override public RuntimeException buildException() {
            return new RuntimeException(message);
        }
    }

    private record AlwaysNonRetriable() implements FailureMode {
        @Override public boolean shouldFailOnCall(int n) { return true; }
        @Override public RuntimeException buildException() {
            return new RuntimeException("HTTP error (400): INVALID_ARGUMENT bad request");
        }
    }

    private record AlwaysOk() implements FailureMode {
        @Override public boolean shouldFailOnCall(int n) { return false; }
        @Override public RuntimeException buildException() {
            throw new IllegalStateException("AlwaysOk does not fail");
        }
    }

    private record NThenOk(int n) implements FailureMode {
        @Override public boolean shouldFailOnCall(int call) { return call <= n; }
        @Override public RuntimeException buildException() {
            return new RuntimeException("HTTP error (503): high demand");
        }
    }

    /** {@link StreamingChatModel} that fires the {@link FailureMode} verdict. */
    private record SyntheticStreamingModel(FailureMode mode, AtomicInteger calls)
            implements StreamingChatModel {
        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            int n = calls.incrementAndGet();
            if (mode.shouldFailOnCall(n)) {
                handler.onError(mode.buildException());
                return;
            }
            // Success path — emit a deterministic non-streaming completion so
            // the test assertion can verify "the call eventually succeeded".
            ChatResponse response = ChatResponse.builder()
                    .aiMessage(AiMessage.from("ok-from-failing-provider"))
                    .finishReason(FinishReason.STOP)
                    .build();
            handler.onCompleteResponse(response);
        }
    }
}

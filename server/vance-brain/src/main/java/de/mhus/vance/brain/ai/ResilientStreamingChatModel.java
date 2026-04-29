package de.mhus.vance.brain.ai;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link StreamingChatModel} decorator that adds retry + chain-fallback
 * around a list of {@link ChainEntry chain entries}. Provider-specific
 * concerns stay where they live (in the underlying delegates) — this
 * class is generic and applies to any langchain4j streaming model.
 *
 * <h2>Retry semantics</h2>
 * <ul>
 *   <li>For each chain entry, the call is attempted up to
 *       {@link RetryPolicy#maxAttempts()} times with exponential backoff.</li>
 *   <li>An attempt is retried only if {@link RetryPolicy#shouldRetry(Throwable)}
 *       matches. Anything else propagates immediately.</li>
 *   <li>Once any token has been emitted to the caller, the stream is
 *       considered committed — mid-stream errors propagate without retry,
 *       because the caller has already started consuming partial output
 *       and a re-issue would emit duplicates.</li>
 *   <li>After an entry's attempts are exhausted (or the error isn't in
 *       the retry pattern set), the next chain entry is tried fresh.</li>
 *   <li>If all chain entries fail, the last error is forwarded to the
 *       caller's {@code onError}.</li>
 * </ul>
 *
 * <p>Backoff scheduling uses a small daemon thread; the actual retry
 * runs on whichever thread the underlying provider uses, so it doesn't
 * leak our scheduler thread into the streaming pipeline.
 */
public class ResilientStreamingChatModel implements StreamingChatModel {

    private static final Logger log = LoggerFactory.getLogger(ResilientStreamingChatModel.class);

    /**
     * Single shared scheduler — used only to delay the retry trigger.
     * Daemon threads keep the JVM exitable.
     */
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ai-resilient-retry");
                t.setDaemon(true);
                return t;
            });

    private final List<ChainEntry> chain;
    private final @Nullable Consumer<String> userNotifier;

    public ResilientStreamingChatModel(List<ChainEntry> chain) {
        this(chain, null);
    }

    /**
     * @param chain         non-empty fallback chain
     * @param userNotifier  optional human-readable feedback hook fired on
     *                      every retry and chain-advance — used by the
     *                      engine call-site to push a status ping into
     *                      the user-progress side-channel so the user
     *                      understands why a turn is taking longer.
     *                      {@code null} disables the hook.
     */
    public ResilientStreamingChatModel(List<ChainEntry> chain, @Nullable Consumer<String> userNotifier) {
        if (chain == null || chain.isEmpty()) {
            throw new IllegalArgumentException("chain must contain at least one entry");
        }
        this.chain = List.copyOf(chain);
        this.userNotifier = userNotifier;
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler caller) {
        attempt(0, 1, request, caller, null);
    }

    /**
     * Attempts {@code chain[chainIdx]} on the {@code attempt}-th try.
     * Recurses on retry / chain-advance; terminates by calling the
     * caller's handler.
     *
     * @param chainIdx       index into {@link #chain}
     * @param attempt        1-indexed try counter for the current entry
     * @param request        the original request (re-issued on retry)
     * @param caller         the upstream handler we're decorating
     * @param previousError  the error from the previously exhausted entry
     *                       (used as final cause if everything fails)
     */
    private void attempt(int chainIdx,
                         int attempt,
                         ChatRequest request,
                         StreamingChatResponseHandler caller,
                         Throwable previousError) {
        if (chainIdx >= chain.size()) {
            Throwable cause = previousError != null
                    ? previousError
                    : new RuntimeException("no chain entries");
            caller.onError(new AiChatException(
                    "All " + chain.size() + " chat-model chain entries exhausted", cause));
            return;
        }
        ChainEntry entry = chain.get(chainIdx);
        AtomicBoolean emitted = new AtomicBoolean(false);

        StreamingChatResponseHandler internal = new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                if (partial != null && !partial.isEmpty()) {
                    emitted.set(true);
                }
                caller.onPartialResponse(partial);
            }

            @Override
            public void onCompleteResponse(ChatResponse complete) {
                caller.onCompleteResponse(complete);
            }

            @Override
            public void onError(Throwable error) {
                handleError(chainIdx, attempt, request, caller, entry, emitted.get(), error);
            }
        };

        try {
            entry.delegate().chat(request, internal);
        } catch (RuntimeException synchronousFail) {
            // Some providers throw synchronously on bad request rather
            // than calling onError — funnel both paths through the same
            // handler so retry / chain-advance still applies.
            handleError(chainIdx, attempt, request, caller, entry,
                    emitted.get(), synchronousFail);
        }
    }

    private void handleError(int chainIdx,
                             int attempt,
                             ChatRequest request,
                             StreamingChatResponseHandler caller,
                             ChainEntry entry,
                             boolean emitted,
                             Throwable error) {
        if (emitted) {
            // Caller already saw partials; cannot replay safely.
            log.warn("ResilientChatModel '{}': mid-stream error after first partial — "
                    + "propagating without retry: {}", entry.label(), errorSummary(error));
            caller.onError(error);
            return;
        }
        if (!entry.policy().shouldRetry(error)) {
            // Genuine error, not transient — try next chain entry (which
            // for Phase A means: there is none, so we fail). We pass the
            // error so the final exception preserves the cause.
            log.warn("ResilientChatModel '{}': non-retriable error → advance: {}",
                    entry.label(), errorSummary(error));
            notifyChainAdvance(entry, chainIdx, error);
            attempt(chainIdx + 1, 1, request, caller, error);
            return;
        }
        if (attempt < entry.policy().maxAttempts()) {
            long backoffMs = entry.policy().backoffFor(attempt).toMillis();
            log.warn("ResilientChatModel '{}': transient failure (attempt {}/{}), "
                            + "retry in {}ms — {}",
                    entry.label(), attempt, entry.policy().maxAttempts(),
                    backoffMs, errorSummary(error));
            notifyRetry(entry, attempt, backoffMs, error);
            CompletableFuture.runAsync(
                    () -> attempt(chainIdx, attempt + 1, request, caller, error),
                    delayed(backoffMs));
            return;
        }
        // Budget for this entry exhausted — advance to next chain entry
        // (for Phase A: none, so the recursion call ends with all-exhausted).
        log.warn("ResilientChatModel '{}': retry budget exhausted after {} attempts → advance",
                entry.label(), entry.policy().maxAttempts());
        notifyChainAdvance(entry, chainIdx, error);
        attempt(chainIdx + 1, 1, request, caller, error);
    }

    private void notifyRetry(ChainEntry entry, int attempt, long backoffMs, Throwable error) {
        Consumer<String> n = userNotifier;
        if (n == null) return;
        try {
            n.accept(String.format("%s transient failure — %s · retry %d/%d in %.1fs",
                    entry.label(),
                    errorSummary(error),
                    attempt,
                    entry.policy().maxAttempts(),
                    backoffMs / 1000.0));
        } catch (RuntimeException notifyFail) {
            // Resilience hook must never break the resilience itself.
            log.debug("userNotifier threw on retry: {}", notifyFail.toString());
        }
    }

    private void notifyChainAdvance(ChainEntry entry, int chainIdx, Throwable error) {
        Consumer<String> n = userNotifier;
        if (n == null) return;
        String next = chainIdx + 1 < chain.size() ? chain.get(chainIdx + 1).label() : "<exhausted>";
        try {
            n.accept(String.format("%s exhausted — %s · falling back to %s",
                    entry.label(),
                    errorSummary(error),
                    next));
        } catch (RuntimeException notifyFail) {
            log.debug("userNotifier threw on chain-advance: {}", notifyFail.toString());
        }
    }

    private static java.util.concurrent.Executor delayed(long delayMs) {
        return r -> SCHEDULER.schedule(r, delayMs, TimeUnit.MILLISECONDS);
    }

    private static String errorSummary(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        if (msg == null) {
            return root.getClass().getSimpleName();
        }
        // Trim multi-line provider error bodies for log hygiene.
        int newline = msg.indexOf('\n');
        return newline > 0 ? msg.substring(0, newline) : msg;
    }
}

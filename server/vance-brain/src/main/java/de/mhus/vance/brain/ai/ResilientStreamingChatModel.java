package de.mhus.vance.brain.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
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
 *   <li>A stream that <em>completes successfully</em> but carries neither
 *       text nor a tool call is treated as a transient empty response and
 *       retried on the same terms (only while nothing has been emitted).
 *       Some providers (notably Gemini) return such empty completions
 *       instead of throwing — the exception path never sees them, yet
 *       they are just as recoverable. On exhaustion the empty response is
 *       still delivered to the caller so downstream empty-reply handling
 *       (e.g. an engine parking the worker) is unchanged.</li>
 *   <li><b>Exception:</b> an empty completion that reports
 *       {@link FinishReason#LENGTH} is <em>not</em> retried. It means the
 *       model hit its output-token cap before producing anything visible —
 *       typically a reasoning model whose {@code reasoning_content} ate the
 *       whole {@code max_tokens} budget. Re-issuing the identical request
 *       hits the identical wall, so the retries only burn tokens and
 *       wall-clock. Chain-advance still applies: a fallback entry may carry
 *       a larger cap and actually succeed.</li>
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
     * Upper bound on retries for a <em>successful but empty</em>
     * completion, independent of (and never exceeding) the entry's
     * {@link RetryPolicy#maxAttempts()}. An empty reply usually recovers
     * on the very next attempt, so a small cap avoids stacking several
     * full stream timeouts on a provider that is simply returning blanks.
     */
    private static final int EMPTY_MAX_ATTEMPTS = 3;

    /**
     * Synthetic cause used purely for log / notifier messages when an
     * empty completion drives a retry or chain-advance. Never thrown to
     * the caller — the empty response itself is delivered on exhaustion.
     */
    private static final AiChatException EMPTY_RESPONSE = new AiChatException(
            "streaming completed with an empty response (neither text nor a tool call)");

    /**
     * Variant of {@link #EMPTY_RESPONSE} for the deterministic case: the
     * completion is empty <em>and</em> reports {@link FinishReason#LENGTH},
     * i.e. the output-token cap was reached before any visible content.
     * Also never thrown to the caller.
     */
    private static final AiChatException EMPTY_AT_OUTPUT_CAP = new AiChatException(
            "streaming hit the output-token cap before emitting text or a tool call "
                    + "(finish=LENGTH) — raise maxTokens or reduce reasoning effort");

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
    private final @Nullable ToolLimitLearner toolLimitLearner;

    public ResilientStreamingChatModel(List<ChainEntry> chain) {
        this(chain, null, null);
    }

    public ResilientStreamingChatModel(
            List<ChainEntry> chain, @Nullable Consumer<String> userNotifier) {
        this(chain, userNotifier, null);
    }

    /**
     * @param chain         non-empty fallback chain
     * @param toolLimitLearner optional sink for a learned {@code tools}-array
     *                      cap (see {@link ToolLimitError}); {@code null}
     *                      only skips the learning, not the fast-fail
     * @param userNotifier  optional human-readable feedback hook fired on
     *                      every retry and chain-advance — used by the
     *                      engine call-site to push a status ping into
     *                      the user-progress side-channel so the user
     *                      understands why a turn is taking longer.
     *                      {@code null} disables the hook.
     */
    public ResilientStreamingChatModel(
            List<ChainEntry> chain,
            @Nullable Consumer<String> userNotifier,
            @Nullable ToolLimitLearner toolLimitLearner) {
        if (chain == null || chain.isEmpty()) {
            throw new IllegalArgumentException("chain must contain at least one entry");
        }
        this.chain = List.copyOf(chain);
        this.userNotifier = userNotifier;
        this.toolLimitLearner = toolLimitLearner;
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
            public void onPartialThinking(PartialThinking partialThinking) {
                // Forward reasoning deltas without flipping `emitted`:
                // that flag guards against replaying answer *content* on
                // retry. Reasoning is a dim side-channel, and an
                // empty-after-reasoning completion is exactly a case we
                // still want to retry — a re-streamed thought is a
                // tolerable cosmetic duplicate.
                caller.onPartialThinking(partialThinking);
            }

            @Override
            public void onCompleteResponse(ChatResponse complete) {
                // Successful-but-empty completion (no text, no tool call).
                // Only retriable while nothing has been emitted — a
                // re-issue after partials would duplicate output.
                if (!emitted.get() && isEmpty(complete)) {
                    handleEmptyComplete(chainIdx, attempt, request, caller, entry, complete);
                    return;
                }
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
        String errorText = ToolLimitError.messageOf(error);
        if (ToolLimitError.isTooManyTools(errorText)) {
            // Request-shape rejection, not a provider hiccup: the endpoint
            // never looked at the model, and every remaining chain entry
            // will answer the same way. Advancing would burn the fallback
            // for nothing (2026-08-12: both entries died on the identical
            // 400), so fail now — and remember the real cap so the next
            // turn's tool-surface budget cuts to fit.
            int requested = request.toolSpecifications() == null
                    ? 0 : request.toolSpecifications().size();
            java.util.OptionalInt learned = java.util.OptionalInt.empty();
            if (toolLimitLearner != null) {
                try {
                    learned = toolLimitLearner.learn(entry.label(), errorText, requested);
                } catch (RuntimeException learnFail) {
                    log.debug("toolLimitLearner threw: {}", learnFail.toString());
                }
            }
            log.warn("ResilientChatModel '{}': endpoint rejected {} tool schemas as too many — "
                            + "not advancing the chain (same request shape everywhere): {}",
                    entry.label(), requested, errorSummary(error));
            // Only promise a different outcome when the cap is actually known
            // now. Without a learned number the next turn builds the very same
            // manifest, and "retry" would send the caller into an identical
            // failure — the durable fix is `maxTools:` in the model document.
            String remedy = learned.isPresent()
                    ? " The endpoint's limit of " + learned.getAsInt()
                            + " is now known — retry the turn."
                    : " The endpoint stated no limit, so a retry would fail the same way:"
                            + " set 'maxTools:' for this model (or its provider) first.";
            caller.onError(new AiChatException(
                    "Tool manifest too large for " + entry.label() + " (" + requested
                            + " schemas)." + remedy,
                    error));
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

    /**
     * Retry / advance / deliver logic for a successful-but-empty
     * completion. Mirrors {@link #handleError}'s retry+chain-advance
     * shape, but the terminal action differs: instead of forwarding an
     * error, the empty response is delivered via {@code onCompleteResponse}
     * so the caller keeps its existing empty-reply handling.
     */
    private void handleEmptyComplete(int chainIdx,
                                     int attempt,
                                     ChatRequest request,
                                     StreamingChatResponseHandler caller,
                                     ChainEntry entry,
                                     ChatResponse complete) {
        // finish=LENGTH is a deterministic wall, not a glitch: the model
        // spent its whole output-token budget before emitting anything
        // visible. Re-issuing the same request reproduces it exactly, so
        // skip straight to chain-advance / delivery.
        boolean atOutputCap = isAtOutputCap(complete);
        AiChatException cause = atOutputCap ? EMPTY_AT_OUTPUT_CAP : EMPTY_RESPONSE;
        int maxAttempts = Math.min(entry.policy().maxAttempts(), EMPTY_MAX_ATTEMPTS);
        if (!atOutputCap && attempt < maxAttempts) {
            long backoffMs = entry.policy().backoffFor(attempt).toMillis();
            log.warn("ResilientChatModel '{}': empty response (attempt {}/{}), retry in {}ms",
                    entry.label(), attempt, maxAttempts, backoffMs);
            notifyRetry(entry, attempt, backoffMs, cause);
            CompletableFuture.runAsync(
                    () -> attempt(chainIdx, attempt + 1, request, caller, cause),
                    delayed(backoffMs));
            return;
        }
        String why = atOutputCap
                ? "empty response at the output-token cap (finish=LENGTH, maxOutputTokens="
                        + maxOutputTokens(request) + ") — not retriable"
                : "empty response, retry budget exhausted after " + attempt + " attempt(s)";
        if (chainIdx + 1 < chain.size()) {
            log.warn("ResilientChatModel '{}': {} → advance", entry.label(), why);
            notifyChainAdvance(entry, chainIdx, cause);
            attempt(chainIdx + 1, 1, request, caller, cause);
            return;
        }
        // No provider produced a non-empty reply. Deliver the empty
        // response unchanged — the caller (e.g. the engine loop) decides
        // how to treat a genuine empty completion. The finish reason
        // travels with it, so the engine can tell "hit the cap" from
        // "provider returned blanks" when it words its user-facing message.
        log.warn("ResilientChatModel '{}': {} — delivering empty", entry.label(), why);
        caller.onCompleteResponse(complete);
    }

    /** True when a completed response carries neither text nor a tool call. */
    private static boolean isEmpty(@Nullable ChatResponse response) {
        if (response == null) return true;
        AiMessage message = response.aiMessage();
        if (message == null) return true;
        if (message.hasToolExecutionRequests()) return false;
        String text = message.text();
        return text == null || text.isBlank();
    }

    /**
     * True when the (already known-empty) response was cut off by the
     * output-token cap rather than ending on its own. Reasoning models on
     * the OpenAI wire hit this whenever {@code reasoning_content} consumes
     * the whole {@code max_tokens} budget: a well-formed 200 response with
     * {@code finish_reason: "length"} and no content at all.
     */
    private static boolean isAtOutputCap(@Nullable ChatResponse response) {
        return response != null && response.finishReason() == FinishReason.LENGTH;
    }

    /** The request's output cap, for the log line; {@code null} when unset. */
    private static @Nullable Integer maxOutputTokens(ChatRequest request) {
        return request.parameters() == null ? null : request.parameters().maxOutputTokens();
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

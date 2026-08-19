package de.mhus.vance.brain.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ChatModel} decorator adding retry + chain-fallback around a
 * list of {@link SyncChainEntry chain entries} — the sync counterpart of
 * {@link ResilientStreamingChatModel}, with the same rules and the same
 * {@link RetryPolicy}.
 *
 * <p>Until this existed, a synchronous call had neither: engines driving
 * a tool loop got the full chain, but everything calling
 * {@code AiChat.chatModel()} — Jeltz, Marvin, Slartibartfast's phases,
 * memory compaction, Eddie's triage, {@code LightLlmService} — ran once
 * against the primary and surfaced whatever came back.
 *
 * <h2>Why this is simpler than the streaming twin</h2>
 *
 * <p>A sync call is atomic: either a {@link ChatResponse} comes back or
 * an exception does, and nothing has reached the caller in between. The
 * streaming version's hardest rule — once a token has been emitted the
 * stream is committed and must not be re-issued — has no analogue here,
 * so retry is unconditionally safe and the whole thing is a loop rather
 * than a recursion through callbacks.
 *
 * <h2>Retry semantics</h2>
 * <ul>
 *   <li>Each entry is attempted up to {@link RetryPolicy#maxAttempts()}
 *       times with exponential backoff, retried only when
 *       {@link RetryPolicy#shouldRetry(Throwable)} matches.</li>
 *   <li>A response carrying neither text nor a tool call is treated as a
 *       transient empty reply and retried up to {@link #EMPTY_MAX_ATTEMPTS}
 *       times — unless it reports {@link FinishReason#LENGTH}, which is a
 *       deterministic wall (the output-token cap was spent before anything
 *       visible), where re-issuing reproduces it exactly.</li>
 *   <li>An oversized tool manifest fails immediately without advancing:
 *       every entry would reject the identical request shape.</li>
 *   <li>Exhausting an entry advances to the next; exhausting the last
 *       throws {@link AiChatException} with the final cause, or delivers
 *       the empty response when that is what all of them produced.</li>
 * </ul>
 *
 * <h2>Deadline</h2>
 *
 * <p>{@code deadline} bounds how long this decorator keeps <em>trying</em>,
 * measured from the first attempt. It is checked before starting a new
 * attempt and never interrupts one in flight — a running request is the
 * HTTP client's timeout to enforce, and cancelling it here would leave a
 * half-read connection behind for no gain.
 *
 * <p>It exists because the sync path has callers with a hard external
 * deadline. {@link RetryPolicy#DEFAULT} allows 5 attempts with 5/10/20/40s
 * backoffs, so a chain of two entries can legitimately spend well over two
 * minutes before giving up — correct for an engine turn nobody is timing,
 * fatal behind a synchronous HTTP event whose caller gave up long before.
 * Without a deadline the retries continue after the answer has become
 * worthless.
 */
public class ResilientChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(ResilientChatModel.class);

    /**
     * Cap on retries of a <em>successful but empty</em> reply, never
     * exceeding the entry's own budget. Empty replies recover on the next
     * attempt or not at all.
     */
    private static final int EMPTY_MAX_ATTEMPTS = 3;

    private static final AiChatException EMPTY_RESPONSE = new AiChatException(
            "provider returned an empty response (neither text nor a tool call)");

    private static final AiChatException EMPTY_AT_OUTPUT_CAP = new AiChatException(
            "provider hit the output-token cap before producing text or a tool call "
                    + "(finish=LENGTH) — raise maxTokens or reduce reasoning effort");

    private final List<SyncChainEntry> chain;
    private final @Nullable Consumer<String> userNotifier;
    private final @Nullable ToolLimitLearner toolLimitLearner;
    private final @Nullable Duration deadline;
    private final @Nullable Consumer<String> answeredBy;

    public ResilientChatModel(List<SyncChainEntry> chain) {
        this(chain, null, null, null, null);
    }

    /**
     * @param chain            non-empty fallback chain
     * @param userNotifier     human-readable feedback on every retry and
     *                         chain-advance, for the user-progress channel
     * @param toolLimitLearner sink for a learned {@code tools}-array cap
     * @param deadline         wall-clock budget for the whole call; see
     *                         the class note. {@code null} = unbounded
     * @param answeredBy       fired once with the label of the entry that
     *                         produced the returned response — the only
     *                         place that knows it, since after a fallback
     *                         it is not the primary
     */
    public ResilientChatModel(
            List<SyncChainEntry> chain,
            @Nullable Consumer<String> userNotifier,
            @Nullable ToolLimitLearner toolLimitLearner,
            @Nullable Duration deadline,
            @Nullable Consumer<String> answeredBy) {
        if (chain == null || chain.isEmpty()) {
            throw new IllegalArgumentException("chain must contain at least one entry");
        }
        this.chain = List.copyOf(chain);
        this.userNotifier = userNotifier;
        this.toolLimitLearner = toolLimitLearner;
        this.deadline = deadline;
        this.answeredBy = answeredBy;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        long startNanos = System.nanoTime();
        Throwable lastError = null;
        ChatResponse lastEmpty = null;

        for (int chainIdx = 0; chainIdx < chain.size(); chainIdx++) {
            SyncChainEntry entry = chain.get(chainIdx);
            int emptyBudget = Math.min(entry.policy().maxAttempts(), EMPTY_MAX_ATTEMPTS);
            int emptyAttempts = 0;

            for (int attempt = 1; attempt <= entry.policy().maxAttempts(); attempt++) {
                ChatResponse response;
                try {
                    response = entry.delegate().chat(request);
                } catch (RuntimeException error) {
                    lastError = error;
                    ToolLimitVerdict verdict = toolLimitVerdict(request, entry, error);
                    if (verdict != null) {
                        throw verdict.toException(error);
                    }
                    if (!entry.policy().shouldRetry(error)) {
                        log.warn("ResilientChatModel '{}': non-retriable error → {}: {}",
                                entry.label(), nextStep(chainIdx), errorSummary(error));
                        notifyChainAdvance(entry, chainIdx, error);
                        break;
                    }
                    if (attempt >= entry.policy().maxAttempts()) {
                        log.warn("ResilientChatModel '{}': retry budget exhausted after {} "
                                + "attempts → {}", entry.label(), attempt, nextStep(chainIdx));
                        notifyChainAdvance(entry, chainIdx, error);
                        break;
                    }
                    if (!sleepBeforeRetry(entry, attempt, startNanos, error)) {
                        throw exhausted(lastError);
                    }
                    continue;
                }

                if (!isEmpty(response)) {
                    reportAnsweredBy(entry);
                    return response;
                }

                // Successful call, empty reply. Same retry/advance shape as
                // an error, but the terminal action differs: the response is
                // handed back so the caller's own empty-reply handling stays
                // in charge.
                lastEmpty = response;
                boolean atOutputCap = isAtOutputCap(response);
                AiChatException cause = atOutputCap ? EMPTY_AT_OUTPUT_CAP : EMPTY_RESPONSE;
                lastError = cause;
                emptyAttempts++;
                if (atOutputCap || emptyAttempts >= emptyBudget) {
                    log.warn("ResilientChatModel '{}': {} → {}", entry.label(),
                            atOutputCap
                                    ? "empty response at the output-token cap (finish=LENGTH)"
                                    : "empty response, budget exhausted after "
                                            + emptyAttempts + " attempt(s)",
                            nextStep(chainIdx));
                    notifyChainAdvance(entry, chainIdx, cause);
                    break;
                }
                if (attempt >= entry.policy().maxAttempts()) {
                    notifyChainAdvance(entry, chainIdx, cause);
                    break;
                }
                if (!sleepBeforeRetry(entry, attempt, startNanos, cause)) {
                    reportAnsweredBy(entry);
                    return response;
                }
            }

            if (deadlineExpired(startNanos) && chainIdx + 1 < chain.size()) {
                log.warn("ResilientChatModel: deadline {} reached — not advancing to '{}'",
                        deadline, chain.get(chainIdx + 1).label());
                break;
            }
        }

        if (lastEmpty != null) {
            // No entry produced a non-empty reply. Deliver the last empty
            // one unchanged, finish reason included, so the caller can tell
            // "hit the cap" from "provider returned blanks".
            log.warn("ResilientChatModel: all {} entries returned empty — delivering empty",
                    chain.size());
            return lastEmpty;
        }
        throw exhausted(lastError);
    }

    // ──────────────────── helpers ────────────────────

    /**
     * Waits out the backoff unless the deadline forbids it. Returns
     * {@code false} when the caller must stop trying — either the budget
     * is spent already, or sleeping would spend it.
     */
    private boolean sleepBeforeRetry(SyncChainEntry entry, int attempt,
            long startNanos, Throwable error) {
        long backoffMs = entry.policy().backoffFor(attempt).toMillis();
        if (deadline != null) {
            long remainingMs = deadline.toMillis()
                    - Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            if (remainingMs <= backoffMs) {
                // Deliberately not "sleep for whatever is left": the retry
                // would start with no time to complete, so it only delays
                // the failure the caller is already waiting for.
                log.warn("ResilientChatModel '{}': deadline {} leaves {}ms — "
                                + "stopping instead of retrying in {}ms",
                        entry.label(), deadline, Math.max(remainingMs, 0), backoffMs);
                return false;
            }
        }
        log.warn("ResilientChatModel '{}': transient failure (attempt {}/{}), retry in {}ms — {}",
                entry.label(), attempt, entry.policy().maxAttempts(),
                backoffMs, errorSummary(error));
        notifyRetry(entry, attempt, backoffMs, error);
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }

    /**
     * What happens after this entry gives up — named in the log because
     * "advance" on the last entry reads like there is somewhere to go.
     * Two layers stack here (each model's own retries, then the chain
     * across models), so a reader needs to see which one hit the end.
     */
    private String nextStep(int chainIdx) {
        return chainIdx + 1 < chain.size()
                ? "advance to " + chain.get(chainIdx + 1).label()
                : "give up (last entry)";
    }

    private boolean deadlineExpired(long startNanos) {
        return deadline != null
                && System.nanoTime() - startNanos >= deadline.toNanos();
    }

    private AiChatException exhausted(@Nullable Throwable lastError) {
        Throwable cause = lastError != null
                ? lastError
                : new RuntimeException("no chain entries");
        return new AiChatException(
                "All " + chain.size() + " chat-model chain entries exhausted", cause);
    }

    private void reportAnsweredBy(SyncChainEntry entry) {
        Consumer<String> sink = answeredBy;
        if (sink == null) return;
        try {
            sink.accept(entry.label());
        } catch (RuntimeException e) {
            // A reporting hook must never break the call it reports on.
            log.debug("syncAnsweredBy threw: {}", e.toString());
        }
    }

    /**
     * Detects the "too many tool schemas" rejection, which is a property
     * of the request shape rather than of the model: every remaining entry
     * would answer with the identical 400, so advancing burns the fallback
     * for nothing. Returns {@code null} when this is an ordinary error.
     */
    private @Nullable ToolLimitVerdict toolLimitVerdict(
            ChatRequest request, SyncChainEntry entry, Throwable error) {
        String errorText = ToolLimitError.messageOf(error);
        if (!ToolLimitError.isTooManyTools(errorText)) {
            return null;
        }
        int requested = request.toolSpecifications() == null
                ? 0 : request.toolSpecifications().size();
        OptionalInt learned = OptionalInt.empty();
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
        return new ToolLimitVerdict(entry.label(), requested, learned);
    }

    private record ToolLimitVerdict(String label, int requested, OptionalInt learned) {
        AiChatException toException(Throwable cause) {
            // Only promise a different outcome when the cap is actually
            // known now — otherwise the next turn builds the same manifest
            // and a "retry" walks into the identical failure.
            String remedy = learned.isPresent()
                    ? " The endpoint's limit of " + learned.getAsInt()
                            + " is now known — retry the turn."
                    : " The endpoint stated no limit, so a retry would fail the same way:"
                            + " set 'maxTools:' for this model (or its provider) first.";
            return new AiChatException(
                    "Tool manifest too large for " + label + " (" + requested
                            + " schemas)." + remedy, cause);
        }
    }

    /** True when a response carries neither text nor a tool call. */
    private static boolean isEmpty(@Nullable ChatResponse response) {
        if (response == null) return true;
        AiMessage message = response.aiMessage();
        if (message == null) return true;
        if (message.hasToolExecutionRequests()) return false;
        String text = message.text();
        return text == null || text.isBlank();
    }

    private static boolean isAtOutputCap(@Nullable ChatResponse response) {
        return response != null && response.finishReason() == FinishReason.LENGTH;
    }

    private void notifyRetry(SyncChainEntry entry, int attempt, long backoffMs, Throwable error) {
        Consumer<String> n = userNotifier;
        if (n == null) return;
        try {
            n.accept(String.format("%s transient failure — %s · retry %d/%d in %.1fs",
                    entry.label(), errorSummary(error), attempt,
                    entry.policy().maxAttempts(), backoffMs / 1000.0));
        } catch (RuntimeException notifyFail) {
            log.debug("userNotifier threw on retry: {}", notifyFail.toString());
        }
    }

    private void notifyChainAdvance(SyncChainEntry entry, int chainIdx, Throwable error) {
        Consumer<String> n = userNotifier;
        if (n == null) return;
        String next = chainIdx + 1 < chain.size() ? chain.get(chainIdx + 1).label() : "<exhausted>";
        try {
            n.accept(String.format("%s exhausted — %s · falling back to %s",
                    entry.label(), errorSummary(error), next));
        } catch (RuntimeException notifyFail) {
            log.debug("userNotifier threw on chain-advance: {}", notifyFail.toString());
        }
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
        int newline = msg.indexOf('\n');
        return newline > 0 ? msg.substring(0, newline) : msg;
    }
}

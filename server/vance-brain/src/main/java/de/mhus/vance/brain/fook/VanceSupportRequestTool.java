package de.mhus.vance.brain.fook;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The single LLM-facing entry to Fook. Lets the calling engine
 * file a bug report, feature request, documentation question, or
 * other piece of feedback about <b>Vance itself</b> — explicitly
 * not about the user's project content. The tool is queued
 * (returns immediately), Fook triages later, and the user gets an
 * inbox item with the outcome.
 *
 * <p><b>Why one tool, not three</b> — the {@code type} parameter
 * is an enum with descriptive per-value documentation so the LLM
 * can pick the right kind in one shot. Splitting into
 * {@code vance_bug_report} / {@code vance_feature_request} / …
 * would have orphaned {@code question} and {@code other} or
 * required a fourth tool; the enum is cleaner and lets Fook
 * re-classify if the LLM guesses wrong (see {@code fook} recipe).
 *
 * <p><b>Rate-limit</b> — at most {@link #MAX_CALLS_PER_PROCESS}
 * invocations per {@link ToolInvocationContext#processId()}
 * lifetime. The counter sits in-memory on the pod that handles
 * the call; processes that hop pods get a fresh budget, which is
 * fine for v1 (the budget guards against runaway loops, not
 * against coordinated abuse).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VanceSupportRequestTool implements Tool {

    public static final String NAME = "vance_support_request";

    /** Hard cap per process lifetime. Three is enough for legit
     *  use (the LLM should normally fire zero or one) and stops a
     *  flapping retry loop from spamming Fook with the same
     *  symptom. */
    static final int MAX_CALLS_PER_PROCESS = 3;

    private static final Map<String, Object> SCHEMA = buildSchema();

    private final FookService fookService;
    private final ThinkProcessService thinkProcessService;

    /** Process-id → call count. Stays in memory until the JVM
     *  restarts; processes that finish leave their counter behind
     *  until the next restart. v1 doesn't bother with eviction
     *  because the map grows ~O(processes-per-pod-per-day), which
     *  is small. If that changes, add a scheduled sweeper. */
    private final ConcurrentHashMap<String, AtomicInteger> callCountByProcess
            = new ConcurrentHashMap<>();

    // ─── metadata ───────────────────────────────────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Send a free-form report about **Vance itself** — a bug "
                + "you hit, a feature you'd want, a documentation gap, "
                + "or other feedback. One text parameter: describe what "
                + "happened, what you expected, or what you wish Vance "
                + "did. Fook classifies the type, derives the title, "
                + "and decides severity from your text — you don't pick "
                + "those. Queued for async triage; the user receives an "
                + "inbox item with the outcome. "
                + "Do NOT use this for the user's project content or "
                + "for tasks the user is asking you to do right now — "
                + "only for changes to or problems with Vance as a "
                + "system.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Set<String> labels() {
        // "write" because it persists a ticket; "side-effect" because
        // it sends an inbox notification. Plan-mode strips both.
        return Set.of("write", "side-effect");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    private static Map<String, Object> buildSchema() {
        Map<String, Object> textProp = new LinkedHashMap<>();
        textProp.put("type", "string");
        textProp.put("description",
                "Free-form description of the issue or request. Be "
                        + "concrete: what happened, what you expected, "
                        + "steps to reproduce if applicable. Markdown is "
                        + "fine. Fook reads this and picks the type "
                        + "(bug/feature/question/other), severity, and "
                        + "title from it — you don't supply those.");

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("text", textProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("text"));
        return schema;
    }

    // ─── invoke ─────────────────────────────────────────────────────

    @Override
    public Map<String, Object> invoke(
            Map<String, Object> params, ToolInvocationContext ctx) {
        String processId = ctx.processId();
        if (processId == null || processId.isBlank()) {
            throw new ToolException(
                    "vance_support_request must be called from a think-process");
        }
        if (ctx.userId() == null || ctx.userId().isBlank()) {
            throw new ToolException(
                    "vance_support_request needs an authenticated userId");
        }
        if (ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ToolException(
                    "vance_support_request needs a tenantId");
        }

        // Rate-limit: count BEFORE doing any other work so a barrage
        // of bad params doesn't burn through the budget. Increment is
        // atomic; we roll back the increment when over the cap so
        // future legit calls don't get blocked by the failed attempts.
        AtomicInteger counter = callCountByProcess.computeIfAbsent(
                processId, k -> new AtomicInteger(0));
        int after = counter.incrementAndGet();
        if (after > MAX_CALLS_PER_PROCESS) {
            counter.decrementAndGet();
            throw new ToolException(
                    "Submission budget exhausted (max "
                            + MAX_CALLS_PER_PROCESS + " per process). "
                            + "If you're hitting this, review your previous "
                            + "submissions — they may already cover what "
                            + "you're trying to report.");
        }

        String text = readNonBlank(params, "text");

        SubmissionRequest req = SubmissionRequest.builder()
                .text(text)
                .reporter(TicketReporter.builder()
                        .kind(TicketReporter.Kind.ENGINE)
                        .userId(ctx.userId())
                        .tenantId(ctx.tenantId())
                        .build())
                .context(buildContext(ctx))
                .build();

        String submissionId = fookService.submit(req);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("submissionId", submissionId);
        out.put("status", "queued");
        out.put("remainingBudget", MAX_CALLS_PER_PROCESS - after);
        out.put("note",
                "Fook is triaging your submission asynchronously. The "
                        + "user will receive an inbox item with the "
                        + "outcome (new ticket / merged into existing / "
                        + "discarded). You do not need to wait or check.");
        return out;
    }

    // ─── helpers ────────────────────────────────────────────────────

    private TicketContext buildContext(ToolInvocationContext ctx) {
        String recipe = null;
        String engine = null;
        Optional<ThinkProcessDocument> process =
                thinkProcessService.findById(ctx.processId());
        if (process.isPresent()) {
            recipe = process.get().getRecipeName();
            engine = process.get().getThinkEngine();
        }
        return TicketContext.builder()
                .projectId(ctx.projectId())
                .sessionId(ctx.sessionId())
                .processId(ctx.processId())
                .recipe(recipe)
                .engine(engine)
                .build();
    }

    private static String readNonBlank(Map<String, Object> params, String key) {
        Object raw = params.get(key);
        if (raw == null) {
            throw new ToolException("'" + key + "' is required");
        }
        String s = raw.toString();
        if (s.isBlank()) {
            throw new ToolException("'" + key + "' must not be blank");
        }
        return s;
    }
}

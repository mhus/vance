package de.mhus.vance.brain.fook;

import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.metric.MetricService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Second-stage Fook worker: turns the reporter's session into a
 * distilled analysis report and attaches it to an already-created
 * ticket. See {@code planning/fook-session-report.md} and
 * {@code specification/public/fook-service.md} §11.
 *
 * <p><b>Why this exists.</b> The ticket fixer (Lunkwill) runs on a
 * potentially different system with <em>no</em> access to the
 * reporter's session. A distilled report is the only bridge that
 * carries session context — what the engine was doing, what failed —
 * across that boundary. Fook holds a session reference at triage
 * time; this service uses it while it's still fresh.
 *
 * <p><b>Agentic loop, not a single shot.</b> Sessions can be far
 * larger than a context window, so the report is <em>not</em> produced
 * by cramming a truncated transcript into one LLM call. Instead this
 * service loads the process's active history <em>once</em> (server-
 * side, via {@link ChatMessageService} — Datenhoheit) and runs a
 * bounded ReAct loop over it: each turn is a
 * {@link LightLlmService#callForJson} that returns exactly one action
 * ({@code overview} / {@code search} / {@code grep} / {@code read} /
 * {@code finish}); Fook executes the action against the in-memory
 * message list with plain grep/slice logic and appends the observation
 * to a scratchpad for the next turn. The model works <em>over</em> the
 * data with tools — like a human analyst would — and never receives
 * the whole session at once. Loading messages server-side to grep them
 * is fine; what must never happen is dumping them into the prompt.
 *
 * <p><b>Tenant.</b> Unlike triage (which prefers the {@code _vance}
 * system tenant), the analysis runs in the <em>reporter's</em> tenant
 * and project: that's where the session lives and where the user's own
 * configured provider handles the potentially-sensitive content. The
 * ticket + sidecar still live in {@code _vance}.
 *
 * <p><b>Double gate.</b> The triage flag is a hint; the loop's
 * {@code finish} additionally returns {@code useful} after the model
 * has actually inspected the session, so a flagged-but-empty session
 * produces no sidecar.
 *
 * <p><b>Failure / exhaustion.</b> Non-fatal — the ticket and its inbox
 * item already exist. On any error the ticket is stamped
 * {@code analysisStatus=failed}; running out of steps without a
 * {@code finish} stamps {@code skipped}. No failure inbox item.
 *
 * <p><b>Crash semantics.</b> Queue is JVM-heap-only, like the triage
 * queue — a pod restart loses pending analyses. Accepted: the ticket
 * survives, only the report is missing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FookSessionAnalysisService {

    /** Recipe used as config profile for each loop turn. Bundled,
     *  {@code internal: true}, resolved via the recipe cascade. */
    static final String RECIPE_NAME = "fook-session-analysis";

    /** Micrometer counter name; {@code outcome} tag values below. */
    static final String METRIC = "vance.fook.analysis";
    static final String OUTCOME_WRITTEN = "written";
    static final String OUTCOME_SKIPPED_NOT_USEFUL = "skipped_not_useful";
    static final String OUTCOME_SKIPPED_NO_SESSION = "skipped_no_session";
    static final String OUTCOME_EXHAUSTED = "exhausted";
    static final String OUTCOME_FAILED = "failed";

    /** Permissive schema — the recipe fully specifies the action shape;
     *  {@link #dispatch} tolerates missing/odd fields. */
    private static final Map<String, Object> SCHEMA = Map.of("type", "object");

    /** Default loop-turn budget. Each turn is one LLM call (one action:
     *  search/grep/read OR finish); the seeded overview is free. This is
     *  a safety-net against runaway loops, NOT a target — the model
     *  finishes as soon as it has enough. A focused analysis typically
     *  needs 4–10 investigative calls (a few search/grep + several paged
     *  reads) + finish; 24 leaves comfortable headroom for large
     *  memories that need many read pages, at ~no cost for the common
     *  case (early finish). Override via {@code vance.fook.analysis.max-steps}. */
    static final int DEFAULT_MAX_STEPS = 24;
    /** Max hits returned by a single {@code search}/{@code grep}. */
    static final int MAX_MATCHES = 25;
    /** Chars of context shown per hit in a search/grep result. */
    static final int SNIPPET_CHARS = 240;
    /** Total chars a single {@code read} may return. */
    static final int MAX_READ_CHARS = 8_000;
    /** Scratchpad cap — oldest observations are dropped past this so the
     *  loop's own prompt can never blow the window either. Sized to hold
     *  several full reads plus many search/grep results so findings from
     *  early turns survive until {@code finish} writes the report. */
    static final int MAX_SCRATCHPAD_CHARS = 48_000;

    private final ChatMessageService chatMessageService;
    private final LightLlmService lightLlm;
    private final FookTicketService ticketService;
    private final MetricService metricService;

    /** Loop-turn budget; {@link #DEFAULT_MAX_STEPS} unless overridden.
     *  Field-injected (not a constructor arg) so unit tests that build
     *  the service directly get the default. */
    @org.springframework.beans.factory.annotation.Value(
            "${vance.fook.analysis.max-steps:" + DEFAULT_MAX_STEPS + "}")
    private int maxSteps = DEFAULT_MAX_STEPS;

    private final Queue<AnalysisJob> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger();

    // ─── public API ─────────────────────────────────────────────────

    /** Enqueue an analysis job. Returns immediately; the scheduled tick
     *  does the work. */
    public void enqueue(AnalysisJob job) {
        queue.add(job);
        inFlight.incrementAndGet();
        log.info("Fook: queued session-analysis ticket={} tenant={} session={} process={}",
                job.getTicketId(), job.getTenantId(),
                job.getSessionId(), job.getProcessId());
    }

    /** Approximate count of queued + in-progress analyses. Diagnostic. */
    public int inFlight() {
        return inFlight.get();
    }

    // ─── worker ─────────────────────────────────────────────────────

    @Scheduled(fixedDelayString = "${vance.fook.analysis.tick:PT5S}")
    public void drainQueue() {
        AnalysisJob job;
        while ((job = queue.poll()) != null) {
            try {
                processJob(job);
            } catch (RuntimeException e) {
                log.warn("Fook: session-analysis failed for ticket={}: {}",
                        job.getTicketId(), e.toString());
                safeSetStatus(job.getTicketId(), FookTicketService.ANALYSIS_FAILED);
                metricService.counter(METRIC, "outcome", OUTCOME_FAILED).increment();
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    // ─── loop ───────────────────────────────────────────────────────

    private void processJob(AnalysisJob job) {
        List<ChatMessageDocument> messages = chatMessageService.activeHistory(
                job.getTenantId(), job.getSessionId(), job.getProcessId());
        if (messages.isEmpty()) {
            log.info("Fook: session-analysis ticket={} — no active history, skipping",
                    job.getTicketId());
            ticketService.setAnalysisStatus(
                    job.getTicketId(), FookTicketService.ANALYSIS_SKIPPED);
            metricService.counter(METRIC, "outcome", OUTCOME_SKIPPED_NO_SESSION).increment();
            return;
        }

        // Seed the scratchpad with an overview so the first turn is
        // already oriented and doesn't have to spend a step on it.
        List<String> observations = new ArrayList<>();
        observations.add(overview(messages));

        String report = null;
        boolean useful = false;
        boolean finished = false;

        for (int step = 0; step < maxSteps && !finished; step++) {
            trimScratchpad(observations);
            Map<String, Object> vars = baseVars(job);
            vars.put("stepsLeft", maxSteps - step);
            vars.put("observations", String.join("\n\n", observations));

            Map<String, Object> raw = lightLlm.callForJson(LightLlmRequest.builder()
                    .recipeName(RECIPE_NAME)
                    .userPrompt("Decide your next action.")
                    .pebbleVars(vars)
                    .schema(SCHEMA)
                    .tenantId(job.getTenantId())
                    .projectId(job.getProjectId())
                    .processId(job.getProcessId())
                    .build());

            String action = lower(str(raw.get("action")));
            if ("finish".equals(action)) {
                useful = boolOf(raw.get("useful"));
                report = str(raw.get("report"));
                finished = true;
                break;
            }
            observations.add(dispatch(action, raw, messages));
        }

        if (finished && useful && report != null && !report.isBlank()) {
            ticketService.writeAnalysis(job.getTicketId(), report);
            metricService.counter(METRIC, "outcome", OUTCOME_WRITTEN).increment();
        } else if (finished) {
            log.info("Fook: session-analysis ticket={} — model finished not-useful "
                    + "(reportChars={})", job.getTicketId(),
                    report == null ? 0 : report.length());
            ticketService.setAnalysisStatus(
                    job.getTicketId(), FookTicketService.ANALYSIS_SKIPPED);
            metricService.counter(METRIC, "outcome", OUTCOME_SKIPPED_NOT_USEFUL).increment();
        } else {
            log.info("Fook: session-analysis ticket={} — ran out of steps ({}) "
                    + "without finishing", job.getTicketId(), maxSteps);
            ticketService.setAnalysisStatus(
                    job.getTicketId(), FookTicketService.ANALYSIS_SKIPPED);
            metricService.counter(METRIC, "outcome", OUTCOME_EXHAUSTED).increment();
        }
    }

    private Map<String, Object> baseVars(AnalysisJob job) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("reason", nullToEmpty(job.getReason()));
        vars.put("triageNote", nullToEmpty(job.getTriageNote()));
        vars.put("ticketTitle", nullToEmpty(job.getTicketTitle()));
        vars.put("ticketType", nullToEmpty(job.getTicketType()));
        vars.put("engine", nullToEmpty(job.getEngine()));
        vars.put("recipe", nullToEmpty(job.getRecipe()));
        return vars;
    }

    // ─── tool dispatch ──────────────────────────────────────────────

    private String dispatch(
            @Nullable String action,
            Map<String, Object> raw,
            List<ChatMessageDocument> messages) {
        if (action == null) {
            return "OBSERVATION: no 'action' field in your reply — return one of "
                    + "overview | search | grep | read | finish.";
        }
        return switch (action) {
            case "overview" -> overview(messages);
            case "search" -> search(messages, str(raw.get("query")), false);
            case "grep" -> search(messages, str(raw.get("regex")), true);
            case "read" -> read(messages, intOrNull(raw.get("from")),
                    intOrNull(raw.get("to")));
            default -> "OBSERVATION: unknown action '" + action + "' — use "
                    + "overview | search | grep | read | finish.";
        };
    }

    /** Cheap map of the conversation: size, role histogram, time span,
     *  addressable index range, first/last snippets. */
    static String overview(List<ChatMessageDocument> messages) {
        int n = messages.size();
        Map<String, Integer> roles = new TreeMap<>();
        Instant first = null;
        Instant last = null;
        for (ChatMessageDocument m : messages) {
            roles.merge(roleOf(m), 1, Integer::sum);
            Instant ts = m.getCreatedAt();
            if (ts != null) {
                if (first == null || ts.isBefore(first)) first = ts;
                if (last == null || ts.isAfter(last)) last = ts;
            }
        }
        StringBuilder b = new StringBuilder();
        b.append("OVERVIEW: ").append(n).append(" messages, indices 0..")
                .append(n - 1).append(". Roles ").append(roles).append(".");
        if (first != null) {
            b.append(" Span ").append(first).append(" … ").append(last).append(".");
        }
        b.append("\n[0] ").append(roleOf(messages.get(0))).append(": ")
                .append(snippet(messages.get(0).getContent(), 0));
        if (n > 1) {
            ChatMessageDocument lastMsg = messages.get(n - 1);
            b.append("\n[").append(n - 1).append("] ").append(roleOf(lastMsg))
                    .append(": ").append(snippet(lastMsg.getContent(), 0));
        }
        return b.toString();
    }

    /** Keyword ({@code regex=false}) or regex ({@code regex=true}) hit
     *  list over message contents. Returns at most {@link #MAX_MATCHES}
     *  hits with an index + centred snippet each. */
    static String search(
            List<ChatMessageDocument> messages,
            @Nullable String needle,
            boolean regex) {
        String label = regex ? "GREP" : "SEARCH";
        if (needle == null || needle.isBlank()) {
            return "OBSERVATION: " + label + " needs a non-empty "
                    + (regex ? "'regex'" : "'query'") + ".";
        }
        Pattern pattern;
        try {
            pattern = regex
                    ? Pattern.compile(needle, Pattern.CASE_INSENSITIVE)
                    : Pattern.compile(Pattern.quote(needle), Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return "OBSERVATION: invalid regex '" + needle + "': " + e.getMessage();
        }
        List<String> hits = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < messages.size(); i++) {
            String content = messages.get(i).getContent();
            if (content == null) continue;
            var matcher = pattern.matcher(content);
            if (matcher.find()) {
                total++;
                if (hits.size() < MAX_MATCHES) {
                    hits.add("[" + i + "] " + roleOf(messages.get(i)) + ": "
                            + snippet(content, matcher.start()));
                }
            }
        }
        if (total == 0) {
            return label + " \"" + needle + "\": no matches.";
        }
        StringBuilder b = new StringBuilder(label + " \"" + needle + "\": "
                + total + " match(es)"
                + (total > hits.size() ? " (showing first " + hits.size() + ")" : "")
                + ". Use read{from,to} to see full messages.\n");
        b.append(String.join("\n", hits));
        return b.toString();
    }

    /** Full content of an inclusive index range, capped at
     *  {@link #MAX_READ_CHARS}. */
    static String read(
            List<ChatMessageDocument> messages,
            @Nullable Integer from,
            @Nullable Integer to) {
        int n = messages.size();
        int lo = from == null ? 0 : Math.max(0, from);
        int hi = to == null ? lo : Math.min(n - 1, to);
        if (lo >= n) {
            return "OBSERVATION: index " + lo + " is out of range (0.." + (n - 1) + ").";
        }
        if (hi < lo) hi = lo;
        StringBuilder b = new StringBuilder("READ [" + lo + ".." + hi + "]:\n");
        int budget = MAX_READ_CHARS;
        for (int i = lo; i <= hi && budget > 0; i++) {
            ChatMessageDocument m = messages.get(i);
            String content = m.getContent() == null ? "" : m.getContent();
            if (content.length() > budget) {
                content = content.substring(0, budget) + "\n… [truncated]";
            }
            budget -= content.length();
            b.append("\n[").append(i).append("] ").append(roleOf(m));
            String sender = m.getSenderDisplayName();
            if (sender != null && !sender.isBlank()) {
                b.append(" (").append(sender).append(")");
            }
            b.append(":\n").append(content).append("\n");
            if (budget <= 0 && i < hi) {
                b.append("\n… [read budget reached at index ").append(i)
                        .append("; continue with read{from:").append(i + 1)
                        .append("}]");
            }
        }
        return b.toString();
    }

    // ─── helpers ────────────────────────────────────────────────────

    private void trimScratchpad(List<String> observations) {
        int total = 0;
        for (String o : observations) total += o.length();
        // Drop oldest (keep the seeded overview at index 0 if possible)
        // until under budget.
        while (total > MAX_SCRATCHPAD_CHARS && observations.size() > 2) {
            String dropped = observations.remove(1);
            total -= dropped.length();
        }
    }

    private void safeSetStatus(String ticketId, String status) {
        try {
            ticketService.setAnalysisStatus(ticketId, status);
        } catch (RuntimeException e) {
            log.warn("Fook: could not stamp analysisStatus={} on ticket={}: {}",
                    status, ticketId, e.getMessage());
        }
    }

    private static String roleOf(ChatMessageDocument m) {
        return m.getRole() == null ? "UNKNOWN" : m.getRole().name();
    }

    private static String snippet(@Nullable String content, int around) {
        if (content == null || content.isBlank()) return "(empty)";
        String flat = content.strip().replaceAll("\\s+", " ");
        if (flat.length() <= SNIPPET_CHARS) return flat;
        int start = Math.max(0, Math.min(around - SNIPPET_CHARS / 4,
                flat.length() - SNIPPET_CHARS));
        String slice = flat.substring(start, Math.min(flat.length(), start + SNIPPET_CHARS));
        return (start > 0 ? "…" : "") + slice + "…";
    }

    private static @Nullable String str(@Nullable Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.isBlank() ? null : s;
    }

    private static String lower(@Nullable String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    private static boolean boolOf(@Nullable Object o) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return Boolean.parseBoolean(s.trim());
        return false;
    }

    private static @Nullable Integer intOrNull(@Nullable Object o) {
        if (o instanceof Number num) return num.intValue();
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }

    /**
     * A queued session-analysis unit of work. Carries everything the
     * worker needs so it never reaches back into {@code FookService}
     * state. {@code sessionId}/{@code processId} are guaranteed
     * non-blank by the enqueue-side gate in {@code FookService}.
     */
    @Value
    @Builder
    public static class AnalysisJob {
        String ticketId;
        String submissionId;
        String tenantId;
        @Nullable String projectId;
        String sessionId;
        String processId;
        @Nullable String reason;
        @Nullable String triageNote;
        @Nullable String ticketTitle;
        @Nullable String ticketType;
        @Nullable String engine;
        @Nullable String recipe;
    }
}

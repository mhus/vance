package de.mhus.vance.brain.frankie.tools;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.brain.frankie.FrankieTermination;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Termination tool for the bundled {@code code-review} recipe — the
 * first concrete post-completion-hook application on Frankie.
 *
 * <p>The reviewer worker calls this tool exactly once when its review
 * is done. The result map carries {@code _terminate: true} so Frankie
 * closes the reviewer-process cleanly, and the structured outcome
 * ({@code outcome}, {@code summary}/{@code reason},
 * {@code followUp}) is delivered to the parent worker via the
 * standard {@code ParentNotificationListener} path — see
 * {@code planning/frankie-post-completion-hook.md} §6.
 *
 * <p>Not part of any engine default — only the {@code code-review}
 * recipe (or a tenant override of it) exposes the tool through
 * {@code allowedToolsAdd}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CodeReviewDecideTool implements Tool {

    /** Outcome value: review accepted the worker's final answer as-is. */
    public static final String OUTCOME_APPROVE = "approve";
    /** Outcome value: review wants the worker to fix something. */
    public static final String OUTCOME_REOPEN = "reopen";

    private final ChatMessageService chatMessageService;
    private final ThinkProcessService thinkProcessService;

    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("outcome", Map.of(
                "type", "string",
                "enum", List.of(OUTCOME_APPROVE, OUTCOME_REOPEN),
                "description", "Reviewer decision. `approve` ⇒ the "
                        + "worker's answer stands as-is. `reopen` ⇒ the "
                        + "worker should address the concrete issue "
                        + "described in `reason`."));
        properties.put("summary", Map.of(
                "type", "string",
                "description", "1-2 sentence rationale for an `approve` "
                        + "decision. Required when outcome=approve. "
                        + "Becomes the humanSummary delivered to the "
                        + "parent worker."));
        properties.put("reason", Map.of(
                "type", "string",
                "description", "Concrete problem found. Required when "
                        + "outcome=reopen. Be specific — file path, "
                        + "function name, what's wrong — so the worker "
                        + "can act without guessing."));
        properties.put("followUp", Map.of(
                "type", "string",
                "description", "Optional one-line instruction for the "
                        + "worker on how to address the reason. Used "
                        + "only when outcome=reopen. Example: "
                        + "'Add a null-check for `name` in "
                        + "UserService.validate before the trim().' "));
        SCHEMA = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("outcome"));
    }

    @Override
    public String name() {
        return "code_review_decide";
    }

    @Override
    public String description() {
        return "Signal the reviewer's decision and terminate this "
                + "review process. Pass outcome=approve with a short "
                + "summary, or outcome=reopen with a concrete reason "
                + "(and optionally a followUp instruction). Call "
                + "exactly once — this closes the review.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of("executive");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String outcome = stringOrThrow(params, "outcome").toLowerCase();
        if (!OUTCOME_APPROVE.equals(outcome) && !OUTCOME_REOPEN.equals(outcome)) {
            throw new ToolException(
                    "'outcome' must be 'approve' or 'reopen', got: " + outcome);
        }
        String summary = optString(params, "summary");
        String reason = optString(params, "reason");
        String followUp = optString(params, "followUp");

        if (OUTCOME_APPROVE.equals(outcome)
                && (summary == null || summary.isBlank())) {
            throw new ToolException(
                    "'summary' is required when outcome=approve");
        }
        if (OUTCOME_REOPEN.equals(outcome)
                && (reason == null || reason.isBlank())) {
            throw new ToolException(
                    "'reason' is required when outcome=reopen");
        }

        String humanSummary = buildHumanSummary(outcome, summary, reason, followUp);

        // Persist the decision as an ASSISTANT chat message so
        // ParentNotificationListener.enrichWithLastReply can attach
        // it to the DONE event. Same dance as TrillianDoneTool —
        // Frankie's tool-terminate path skips its natural-stop
        // persistAssistantReply, so we do it explicitly here.
        if (ctx.processId() != null) {
            try {
                ThinkProcessDocument process = thinkProcessService.findById(ctx.processId())
                        .orElse(null);
                if (process != null) {
                    chatMessageService.append(ChatMessageDocument.builder()
                            .tenantId(process.getTenantId())
                            .sessionId(process.getSessionId())
                            .thinkProcessId(process.getId())
                            .role(ChatRole.ASSISTANT)
                            .content(humanSummary)
                            .build());
                }
            } catch (RuntimeException e) {
                log.warn("code_review_decide: failed to persist decision for "
                                + "process='{}': {}",
                        ctx.processId(), e.toString());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(FrankieTermination.RESULT_TERMINATE_KEY, true);
        out.put("outcome", outcome);
        if (summary != null && !summary.isBlank()) {
            out.put("summary", summary);
        }
        if (reason != null && !reason.isBlank()) {
            out.put("reason", reason);
        }
        if (followUp != null && !followUp.isBlank()) {
            out.put("followUp", followUp);
        }
        return out;
    }

    private static String buildHumanSummary(
            String outcome,
            @Nullable String summary,
            @Nullable String reason,
            @Nullable String followUp) {
        StringBuilder sb = new StringBuilder();
        if (OUTCOME_APPROVE.equals(outcome)) {
            sb.append("Review APPROVED. ").append(summary);
        } else {
            sb.append("Review REOPENED. Reason: ").append(reason);
            if (followUp != null && !followUp.isBlank()) {
                sb.append("\n\nFollow-up: ").append(followUp);
            }
        }
        return sb.toString();
    }

    private static String stringOrThrow(@Nullable Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s.trim();
    }

    private static @Nullable String optString(@Nullable Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}

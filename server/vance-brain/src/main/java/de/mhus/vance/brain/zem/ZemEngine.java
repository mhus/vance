package de.mhus.vance.brain.zem;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.zem.ZemAnswerDto;
import de.mhus.vance.api.zem.ZemDecisionDto;
import de.mhus.vance.brain.thinkengine.ParentReport;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Zem — the spawnable shell around {@link ZemService}. One spawn = one
 * decision = terminal {@code DONE}, like Jeltz. All logic lives in the
 * service; the engine only reads its params, writes the result and closes.
 * It exists for two reasons: recipes must name a registered engine (so an
 * {@code engine: zem} profile needs one), and orchestrators (Magrathea,
 * Vogon, Marvin) reach capabilities through the spawn path, with an audit
 * trail in the session browser.
 *
 * <p><b>Params</b> ({@code engineParams} = recipe params + spawn params):
 * <ul>
 *   <li>{@code state} — required; string, map or list</li>
 *   <li>{@code profile} — optional name of an {@code engine: zem} recipe</li>
 *   <li>{@code questions}, {@code model}, {@code stateSelect} — inline
 *       profile; inline questions replace profile questions of the same
 *       name</li>
 * </ul>
 * Spawned from a profile recipe itself (recipe {@code engine: zem} without
 * {@code internal}), the recipe's own params are the inline profile.
 *
 * <p><b>Result</b>, two places:
 * <ul>
 *   <li>the last assistant chat message — a JSON wrapper in Jeltz's shape,
 *       {@code {success: true, data: <ZemDecisionDto>}} or
 *       {@code {success: false, error, message, retryable}}; Magrathea's
 *       agent tasks read it like a Jeltz result</li>
 *   <li>{@code engineParams.zemOutcome} — a compact, Mongo-safe summary
 *       (per question: value + confidence) that
 *       {@link #summarizeForParent} turns into the parent's event</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ZemEngine implements ThinkEngine {

    public static final String NAME = ZemService.ENGINE_NAME;
    public static final String VERSION = "0.1.0";

    static final String PARAM_STATE = "state";
    static final String PARAM_PROFILE = "profile";
    static final String PARAM_MODEL = "model";
    static final String PARAM_QUESTIONS = "questions";
    static final String PARAM_STATE_SELECT = "stateSelect";
    static final String PARAM_OUTCOME = "zemOutcome";

    private static final int AUDIT_STATE_MAX_CHARS = 4000;

    private final ZemService zemService;
    private final ThinkProcessService thinkProcessService;
    private final ObjectMapper objectMapper;

    // ──────────────────── Metadata ────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Zem (Decision)";
    }

    @Override
    public String description() {
        return "Single-shot engine that answers typed questions with calibrated probabilities (System One).";
    }

    @Override
    public String version() {
        return VERSION;
    }

    /** The result is a JSON wrapper, not prose — relays go through the translator. */
    @Override
    public boolean producesUserFacingOutput() {
        return false;
    }

    // ──────────────────── Lifecycle ────────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Zem.start tenant='{}' id='{}'", process.getTenantId(), process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        try {
            decide(process, ctx);
        } catch (RuntimeException e) {
            // Last resort — expected failures are ZemExceptions handled in decide().
            log.error("Zem id='{}' unhandled failure: {}", process.getId(), e.toString(), e);
            writeFailure(process, ctx, "internal_error", String.valueOf(e.getMessage()), false);
        } finally {
            if (process.getStatus() != ThinkProcessStatus.CLOSED) {
                thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
            }
        }
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        if (process.getStatus() == ThinkProcessStatus.CLOSED) {
            return;
        }
        // A restart caught the process mid-call; a decision is idempotent
        // against its params, so it simply runs again.
        log.info("Zem.resume id='{}' rerunning decision", process.getId());
        start(process, ctx);
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext ctx, SteerMessage message) {
        log.warn(
                "Zem.steer id='{}' got '{}' — Zem is single-shot, discarding",
                process.getId(),
                message.getClass().getSimpleName());
    }

    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        List<SteerMessage> drained = ctx.drainPending();
        if (!drained.isEmpty()) {
            log.warn(
                    "Zem.runTurn id='{}' discarded {} pending message(s) — Zem is single-shot",
                    process.getId(),
                    drained.size());
        }
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        if (process.getStatus() != ThinkProcessStatus.CLOSED) {
            writeFailure(
                    process, ctx, ZemException.Reason.CANCELLED.wire(), "Process stopped before completion.", false);
            thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public ParentReport summarizeForParent(ThinkProcessDocument process, ProcessEventType eventType) {
        Object raw = process.getEngineParams() == null
                ? null
                : process.getEngineParams().get(PARAM_OUTCOME);
        if (!(raw instanceof Map<?, ?> outcome)) {
            return ParentReport.of("Zem process " + process.getId() + " "
                    + eventType.name().toLowerCase(Locale.ROOT) + " without a result.");
        }
        return new ParentReport(humanSummary((Map<String, Object>) outcome), (Map<String, Object>) outcome);
    }

    // ──────────────────── Core ────────────────────

    private void decide(ThinkProcessDocument process, ThinkEngineContext ctx) {
        Map<String, Object> params = process.getEngineParams() == null ? Map.of() : process.getEngineParams();
        Object state = params.get(PARAM_STATE);
        if (state == null) {
            writeFailure(process, ctx, ZemException.Reason.INVALID_STATE.wire(), "Missing engineParams.state.", false);
            return;
        }
        auditState(process, ctx, state);
        try {
            ZemProfile inline = ZemProfileParser.parse(inlineSource(process), params);
            ZemDecideRequest request = ZemDecideRequest.builder()
                    .tenantId(process.getTenantId())
                    .projectId(process.getProjectId())
                    .processId(process.getId())
                    .profile(profileName(process, params))
                    .questions(inline.questions())
                    .model(inline.model())
                    .stateSelect(inline.stateSelect().isEmpty() ? null : inline.stateSelect())
                    .build();
            ZemDecisionDto decision = zemService.decide(request, state);
            writeSuccess(process, ctx, decision);
        } catch (ZemException e) {
            log.info("Zem id='{}' failed: {} — {}", process.getId(), e.reason().wire(), e.getMessage());
            writeFailure(process, ctx, e.reason().wire(), e.getMessage(), e.retryable());
        }
    }

    /**
     * The profile to load: an explicit {@code profile} param, otherwise the
     * recipe the process was spawned from — unless that is the generic
     * {@code zem} recipe, which carries no questions of its own.
     */
    private static @Nullable String profileName(ThinkProcessDocument process, Map<String, Object> params) {
        if (params.get(PARAM_PROFILE) instanceof String p && !p.isBlank()) {
            return p.trim();
        }
        String recipe = process.getRecipeName();
        return recipe == null || recipe.isBlank() || NAME.equals(recipe) ? null : recipe;
    }

    private static String inlineSource(ThinkProcessDocument process) {
        return "spawn params of " + process.getId();
    }

    // ──────────────────── Result writers ────────────────────

    private void writeSuccess(ThinkProcessDocument process, ThinkEngineContext ctx, ZemDecisionDto decision) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("success", true);
        wrapper.put("data", decision);
        appendAssistant(process, ctx, wrapper);
        storeOutcome(process, compactOutcome(decision));
    }

    private void writeFailure(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            String error,
            @Nullable String message,
            boolean retryable) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("success", false);
        wrapper.put("error", error);
        wrapper.put("message", message == null ? "" : message);
        wrapper.put("retryable", retryable);
        appendAssistant(process, ctx, wrapper);
        storeOutcome(process, wrapper);
    }

    /** Per question: the value and the confidence — keys are question names, Mongo-safe by validation. */
    static Map<String, Object> compactOutcome(ZemDecisionDto decision) {
        Map<String, Object> answers = new LinkedHashMap<>();
        for (ZemAnswerDto a : decision.getAnswers()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", a.getType().name().toLowerCase(Locale.ROOT));
            switch (a.getType()) {
                case NOUL -> entry.put("noul", a.getNoul());
                case CHOICE -> entry.put("choice", a.getChoice());
                case SCORE -> entry.put("score", a.getScore());
                case REFUSAL -> {
                    // refusal carries no value
                }
            }
            if (a.getConfidence() != null) {
                entry.put("confidence", a.getConfidence());
            }
            answers.put(a.getName(), entry);
        }
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("success", true);
        outcome.put("model", decision.getModel());
        outcome.put("answers", answers);
        return outcome;
    }

    @SuppressWarnings("unchecked")
    static String humanSummary(Map<String, Object> outcome) {
        if (!Boolean.TRUE.equals(outcome.get("success"))) {
            return "Zem decision failed (" + outcome.get("error") + "): " + outcome.get("message");
        }
        StringBuilder out = new StringBuilder("Zem decision (" + outcome.get("model") + "):");
        Object answers = outcome.get("answers");
        if (answers instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Map<String, Object> a = (Map<String, Object>) e.getValue();
                Object value = a.containsKey("choice")
                        ? a.get("choice")
                        : a.containsKey("noul") ? "p(yes)=" + round(a.get("noul")) : "score=" + round(a.get("score"));
                out.append("\n- ").append(e.getKey()).append(": ");
                out.append("refusal".equals(a.get("type")) ? "refused" : value);
                if (a.get("confidence") != null) {
                    out.append(" (confidence ")
                            .append(round(a.get("confidence")))
                            .append(')');
                }
            }
        }
        return out.toString();
    }

    private static String round(@Nullable Object v) {
        return v instanceof Number n ? String.format(Locale.ROOT, "%.2f", n.doubleValue()) : String.valueOf(v);
    }

    private void storeOutcome(ThinkProcessDocument process, Map<String, Object> outcome) {
        Map<String, Object> params =
                new LinkedHashMap<>(process.getEngineParams() == null ? Map.of() : process.getEngineParams());
        params.put(PARAM_OUTCOME, outcome);
        process.setEngineParams(params);
        thinkProcessService.replaceEngineParams(process.getId(), params);
    }

    private void auditState(ThinkProcessDocument process, ThinkEngineContext ctx, Object state) {
        String json;
        try {
            json = objectMapper.writeValueAsString(state);
        } catch (RuntimeException e) {
            json = String.valueOf(state);
        }
        if (json.length() > AUDIT_STATE_MAX_CHARS) {
            json = json.substring(0, AUDIT_STATE_MAX_CHARS) + "…";
        }
        ctx.chatMessageService()
                .append(ChatMessageDocument.builder()
                        .tenantId(process.getTenantId())
                        .sessionId(process.getSessionId())
                        .thinkProcessId(process.getId())
                        .role(ChatRole.USER)
                        .content(json)
                        .build());
    }

    private void appendAssistant(ThinkProcessDocument process, ThinkEngineContext ctx, Map<String, Object> wrapper) {
        String json;
        try {
            json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(wrapper);
        } catch (RuntimeException e) {
            log.warn("Zem id='{}' result serialisation failed: {}", process.getId(), e.toString());
            json = "{\"success\":false,\"error\":\"serialisation_failed\"}";
        }
        ctx.chatMessageService()
                .append(ChatMessageDocument.builder()
                        .tenantId(process.getTenantId())
                        .sessionId(process.getSessionId())
                        .thinkProcessId(process.getId())
                        .role(ChatRole.ASSISTANT)
                        .content(json)
                        .build());
    }
}

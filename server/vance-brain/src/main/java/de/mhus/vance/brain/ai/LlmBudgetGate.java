package de.mhus.vance.brain.ai;

import de.mhus.vance.api.notification.NotificationSeverity;
import de.mhus.vance.brain.notification.NotificationService;
import de.mhus.vance.shared.llmusage.CallAttribution;
import de.mhus.vance.shared.llmusage.LlmBudgetService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The {@link UsageGate} that enforces the cost budgets of
 * {@link LlmBudgetService}.
 *
 * <h2>What is never stopped</h2>
 *
 * <ul>
 *   <li><b>Ungated callers</b> ({@link LlmBudgetService#UNGATED_CALLERS}):
 *       compaction and triage run to the end; their cost is booked and the
 *       limit bites on the next regular call.</li>
 *   <li><b>Free models</b> — a catalog entry with an explicit zero rate
 *       (local Ollama / LM Studio) costs nothing, so an exhausted budget has
 *       nothing to protect there. A fallback chain ending on such a model
 *       keeps a tenant working after the money is gone.</li>
 *   <li><b>Unpriced models</b> — no {@code pricing:} block. Their cost is
 *       unknown and books as zero, so they cannot exhaust a budget and are
 *       let through. The budget status lists them, so the gap is visible.</li>
 * </ul>
 *
 * <h2>Telling someone</h2>
 *
 * The first warning and the first refusal per scope, window and period go to
 * the session whose call noticed it, as a notification, and to the log. Only
 * once per pod: the refusal itself reaches every later caller as an error,
 * and a warning repeated on every call would be noise. The admins of a scope
 * are not addressed directly — the permission SPI deliberately does not list
 * who holds a right.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmBudgetGate implements UsageGate {

    /** Bound on remembered notices; on overflow the map is cleared — a repeat notice is harmless. */
    static final int MAX_NOTICES = 10_000;

    private final LlmBudgetService budgetService;
    private final NotificationService notificationService;
    private final ThinkProcessService thinkProcessService;

    private final Map<String, Boolean> noticesSent = new ConcurrentHashMap<>();

    @Override
    public void admit(CallAttribution attribution, ModelInfo model) {
        if (LlmBudgetService.isUngated(attribution.caller()) || costsNothing(model)) {
            return;
        }
        LlmBudgetService.Verdict verdict = budgetService.check(attribution.tenantId(), attribution.projectId());
        for (LlmBudgetService.WindowStatus warning : verdict.warnings()) {
            noticeOnce(attribution, warning, "warn", NotificationSeverity.WARN, warningText(warning));
        }
        LlmBudgetService.WindowStatus denial = verdict.denial();
        if (denial != null) {
            LlmBudgetExceededException refused = new LlmBudgetExceededException(denial);
            noticeOnce(attribution, denial, "deny", NotificationSeverity.ERROR, refused.getMessage());
            throw refused;
        }
    }

    /** No pricing at all (unknown, booked as zero) or an explicit zero rate. */
    static boolean costsNothing(ModelInfo model) {
        ModelInfo.Pricing p = model.pricing();
        if (p == null) {
            return true;
        }
        return p.inputPerMTok() <= 0
                && p.outputPerMTok() <= 0
                && (p.cacheReadPerMTok() == null || p.cacheReadPerMTok() <= 0)
                && (p.cacheWritePerMTok() == null || p.cacheWritePerMTok() <= 0);
    }

    static String warningText(LlmBudgetService.WindowStatus w) {
        String level =
                switch (w.scope()) {
                    case TENANT -> "Tenant";
                    case PROJECT -> "Project '" + w.scopeId() + "'";
                };
        return String.format(
                Locale.ROOT,
                "%s LLM budget (%s) is at %.0f%% — calls on priced models stop at 100%%.",
                level,
                w.window().label(),
                w.percent());
    }

    private void noticeOnce(
            CallAttribution attribution,
            LlmBudgetService.WindowStatus window,
            String kind,
            NotificationSeverity severity,
            String text) {
        String key = attribution.tenantId()
                + '\0'
                + window.scope()
                + '\0'
                + window.scopeId()
                + '\0'
                + window.window()
                + '\0'
                + window.from()
                + '\0'
                + kind;
        if (noticesSent.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        if (noticesSent.size() > MAX_NOTICES) {
            noticesSent.clear();
        }
        log.warn("LLM budget {} tenant='{}': {}", kind, attribution.tenantId(), text);
        String processId = attribution.processId();
        if (processId == null) {
            return;
        }
        try {
            thinkProcessService
                    .findById(processId)
                    .ifPresent(process -> notificationService.publish(process, text, severity));
        } catch (RuntimeException e) {
            // A notification is a courtesy; the gate's decision stands without it.
            log.debug("LLM budget notice could not be published for process '{}': {}", processId, e.toString());
        }
    }
}

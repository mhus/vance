package de.mhus.vance.brain.slartibartfast.phases;

import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.ConfirmationMode;
import de.mhus.vance.api.slartibartfast.Criterion;
import de.mhus.vance.api.slartibartfast.CriterionOrigin;
import de.mhus.vance.api.slartibartfast.FramedGoal;
import de.mhus.vance.api.slartibartfast.PendingInboxKind;
import de.mhus.vance.api.slartibartfast.PhaseIteration;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.inbox.InboxItemService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * CONFIRMING phase — partitions {@link FramedGoal#getAssumedCriteria()}
 * by confidence and produces the unified
 * {@link ArchitectState#getAcceptanceCriteria()} working set the
 * rest of the lifecycle plans against.
 *
 * <p>Decision logic:
 * <ul>
 *   <li>Every {@link FramedGoal#getStatedCriteria()} entry passes
 *       through unconditionally — the user said it.</li>
 *   <li>Assumed criteria with
 *       {@link Criterion#getConfidence()} ≥
 *       {@link ArchitectState#getConfirmationThreshold()} pass
 *       through with audit note (the planner takes them for
 *       granted because the inference is firm enough).</li>
 *   <li>Assumed criteria below threshold are dropped in this
 *       minimal v1 implementation — recorded as
 *       {@link ValidationCheck} entries with
 *       rule {@code low-confidence-criterion-dropped}.</li>
 * </ul>
 *
 * <p>M6 extends this with an inbox-dialog: low-confidence assumed
 * criteria become inbox questions, the user's answer flips them
 * to {@link CriterionOrigin#USER_CONFIRMED} (passes through) or
 * confirms the drop. Until then dropping is silent-ish — visible
 * in the audit but not blocking.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConfirmingPhase {

    private final InboxItemService inboxItemService;

    /**
     * Mutates {@code state.acceptanceCriteria} and appends a
     * {@link PhaseIteration} plus zero-or-more
     * {@link ValidationCheck} entries describing dropped
     * low-confidence criteria.
     *
     * <p>Idempotent on re-entry: re-running the phase rebuilds
     * {@code acceptanceCriteria} from scratch off
     * {@code state.goal}. That's deliberate — a recovery
     * rollback to CONFIRMING (e.g. after FRAMING re-prompted)
     * must produce a fresh working set, not pile onto the
     * old one.
     *
     * <p>{@link ConfirmationMode#ASK_LOW_CONF}: when low-conf
     * assumed criteria exist and no inbox dialog is yet
     * outstanding, the phase posts a single APPROVAL inbox item
     * listing them and parks (sets
     * {@link ArchitectState#setPendingInboxItemId} +
     * {@link ArchitectState#setPendingInboxKind}). The engine
     * keeps status at CONFIRMING and waits for an
     * {@code InboxAnswer}. Once the engine's drain-handler
     * applies the answer (flips accepted entries' origin to
     * {@link CriterionOrigin#USER_CONFIRMED}, leaves rejected
     * ones with their original {@code INFERRED_*} origin and
     * sub-threshold confidence), the phase is re-entered, sees
     * no pending inbox, and partitions normally — confirmed
     * criteria pass, untouched low-conf entries get dropped.
     */
    public void execute(
            ArchitectState state,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {

        // If we're already waiting for an inbox answer, this is a
        // no-op turn — the engine drain-handler will wake us when
        // the answer arrives and re-call this method.
        if (state.getPendingInboxKind() == PendingInboxKind.CONFIRMATION
                && state.getPendingInboxItemId() != null) {
            log.debug("Slartibartfast id='{}' CONFIRMING parked on inbox '{}'",
                    process.getId(), state.getPendingInboxItemId());
            return;
        }

        FramedGoal goal = state.getGoal();
        if (goal == null) {
            log.warn("Slartibartfast id='{}' CONFIRMING entered without "
                            + "goal — FRAMING didn't populate it",
                    process.getId());
            state.setFailureReason("CONFIRMING entered without a FramedGoal "
                    + "— FRAMING must run first");
            return;
        }

        double threshold = state.getConfirmationThreshold();
        ConfirmationMode mode = state.getConfirmationMode() == null
                ? ConfirmationMode.DROP_LOW_CONF : state.getConfirmationMode();

        List<Criterion> accepted = new ArrayList<>();
        List<Criterion> dropped = new ArrayList<>();
        List<Criterion> lowConfPending = new ArrayList<>();

        // 1. Every stated criterion passes through unconditionally.
        accepted.addAll(goal.getStatedCriteria());

        // 2. Assumed criteria — partition depends on mode.
        for (Criterion c : goal.getAssumedCriteria()) {
            // USER_CONFIRMED entries pass regardless of recorded
            // confidence — the user already said yes via inbox
            // dialog (origin was flipped by the engine's
            // drain-handler before this method re-ran).
            boolean isConfirmed = c.getOrigin() == CriterionOrigin.USER_CONFIRMED;
            boolean isHighConf = c.getConfidence() >= threshold;
            if (isConfirmed || isHighConf) {
                accepted.add(c);
                continue;
            }
            switch (mode) {
                case KEEP_ALL -> accepted.add(c);
                case DROP_LOW_CONF -> dropped.add(c);
                case ASK_LOW_CONF -> lowConfPending.add(c);
            }
        }

        // ASK_LOW_CONF: if we still have unconfirmed low-conf
        // entries, post the inbox dialog and park. The engine
        // re-enters this method after the answer is applied, by
        // which point the entries either flipped to USER_CONFIRMED
        // (and pass) or stayed INFERRED_* low-conf (and we drop
        // here without asking again).
        if (mode == ConfirmationMode.ASK_LOW_CONF && !lowConfPending.isEmpty()) {
            postConfirmationInbox(state, process, lowConfPending);
            appendIteration(state,
                    "stated=" + goal.getStatedCriteria().size()
                            + ", assumed=" + goal.getAssumedCriteria().size()
                            + ", threshold=" + threshold,
                    "parked on inbox '" + state.getPendingInboxItemId() + "' — "
                            + lowConfPending.size() + " low-conf assumed pending",
                    PhaseIteration.IterationOutcome.PASSED);
            return;
        }

        state.setAcceptanceCriteria(accepted);

        // 3. Audit dropped entries as informational ValidationChecks.
        // Replace the current report rather than append — the
        // CONFIRMING-tier checks supersede whatever a previous
        // iteration wrote. Future-phase checks will append.
        List<ValidationCheck> report = new ArrayList<>();
        report.add(ValidationCheck.builder()
                .rule("acceptance-criteria-built")
                .passed(true)
                .message(accepted.size() + " accepted ("
                        + goal.getStatedCriteria().size() + " stated, "
                        + (accepted.size() - goal.getStatedCriteria().size())
                        + " assumed); " + dropped.size()
                        + " low-conf assumed dropped (mode=" + mode + ")")
                .build());
        for (Criterion d : dropped) {
            report.add(ValidationCheck.builder()
                    .rule("low-confidence-criterion-dropped")
                    .passed(true)   // informational, not a failure
                    .offendingId(d.getId())
                    .message("dropped: confidence " + d.getConfidence()
                            + " below threshold " + threshold + " — '"
                            + abbrev(d.getText(), 80) + "'")
                    .build());
        }
        state.setValidationReport(report);

        // 4. Append iteration to the audit log.
        appendIteration(state,
                "stated=" + goal.getStatedCriteria().size()
                        + ", assumed=" + goal.getAssumedCriteria().size()
                        + ", threshold=" + threshold,
                "accepted=" + accepted.size() + ", dropped=" + dropped.size(),
                PhaseIteration.IterationOutcome.PASSED);
    }

    private static void appendIteration(
            ArchitectState state,
            String inputSummary,
            String outputSummary,
            PhaseIteration.IterationOutcome outcome) {
        int attempt = (int) state.getIterations().stream()
                .filter(it -> it.getPhase() == ArchitectStatus.CONFIRMING).count() + 1;
        List<PhaseIteration> log = new ArrayList<>(state.getIterations());
        log.add(PhaseIteration.builder()
                .iteration(attempt)
                .phase(ArchitectStatus.CONFIRMING)
                .triggeredBy(attempt == 1 ? "initial" : "recovery")
                .inputSummary(inputSummary)
                .outputSummary(outputSummary)
                .outcome(outcome)
                .build());
        state.setIterations(log);
    }

    private static String abbrev(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /**
     * Build the inbox-item title + body listing the low-confidence
     * assumed criteria, persist the item, and stash its id on
     * {@code state.pendingInboxItemId} so the engine drain-handler
     * can match an incoming answer.
     *
     * <p>Item type is {@link InboxItemType#APPROVAL} — the user
     * answers yes-or-no for the whole batch. v1 simplification;
     * per-criterion verdicts (STRUCTURE_EDIT) are a possible
     * extension if real-world feedback shows a single bool is
     * too coarse. The criteria payload travels as
     * {@code payload.criteria = [{id, text, confidence}]} so the
     * UI / CLI can render them.
     */
    private void postConfirmationInbox(
            ArchitectState state,
            ThinkProcessDocument process,
            List<Criterion> pending) {
        StringBuilder body = new StringBuilder();
        body.append("Der Planner hat ").append(pending.size())
                .append(" Annahme(n) mit niedriger Konfidenz identifiziert. ")
                .append("Sollen sie als bestätigte Anforderungen "
                        + "übernommen werden?\n\n");
        for (Criterion c : pending) {
            body.append("- [").append(c.getId()).append(", conf=")
                    .append(c.getConfidence()).append("] ")
                    .append(c.getText()).append("\n");
        }
        body.append("\nAntwort: yes → alle als USER_CONFIRMED übernehmen; "
                + "no → alle verwerfen.");

        Map<String, Object> payload = new LinkedHashMap<>();
        List<Map<String, Object>> criteriaPayload = new ArrayList<>();
        for (Criterion c : pending) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", c.getId());
            entry.put("text", c.getText());
            entry.put("origin", c.getOrigin().name());
            entry.put("confidence", c.getConfidence());
            entry.put("rationaleId", c.getRationaleId());
            criteriaPayload.add(entry);
        }
        payload.put("kind", "slartibartfast.confirmation");
        payload.put("runId", state.getRunId());
        payload.put("criteria", criteriaPayload);

        InboxItemDocument toCreate = InboxItemDocument.builder()
                .tenantId(process.getTenantId())
                .originatorUserId("slartibartfast:" + process.getId())
                .assignedToUserId(null)   // unassigned — anyone with access can answer
                .originProcessId(process.getId())
                .originSessionId(process.getSessionId())
                .type(InboxItemType.APPROVAL)
                .criticality(Criticality.NORMAL)
                .title("Slartibartfast: " + pending.size()
                        + " Annahme(n) bestätigen?")
                .body(body.toString())
                .payload(payload)
                .requiresAction(true)
                .build();
        InboxItemDocument saved = inboxItemService.create(toCreate);

        state.setPendingInboxItemId(saved.getId());
        state.setPendingInboxKind(PendingInboxKind.CONFIRMATION);

        log.info("Slartibartfast id='{}' CONFIRMING parked — posted inbox '{}' "
                        + "with {} low-conf criterion(s)",
                process.getId(), saved.getId(), pending.size());
    }
}

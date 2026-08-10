package de.mhus.vance.shared.inbox;

import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.EffectDescription;
import de.mhus.vance.api.inbox.InboxItemType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Indexes {@link InboxEffect} beans by {@link InboxEffect#effectType()}
 * and dispatches an answered item to the matching one.
 *
 * <p><b>Fail-closed throughout.</b> Every ambiguity resolves to "run
 * nothing": no effect type on the item, an unknown type, an abstaining
 * answer, an item type whose answer carries no yes/no, or an
 * unreadable approval flag. The alternative — acting when unsure — is
 * exactly the failure mode this mechanism exists to prevent.
 *
 * <p>Only {@link InboxItemType#APPROVAL} is dispatched in v1: it is the
 * one item type whose answer schema carries a plain yes/no
 * ({@code {"approved": <bool>}}). Effects on richer types would need a
 * different callback shape than approve/reject and are deliberately not
 * guessed at here.
 */
@Service
@Slf4j
public class InboxEffectRegistry {

    private final Map<String, InboxEffect> byType;

    public InboxEffectRegistry(List<InboxEffect> effects) {
        Map<String, InboxEffect> index = new LinkedHashMap<>();
        for (InboxEffect effect : effects) {
            InboxEffect clash = index.put(effect.effectType(), effect);
            if (clash != null) {
                throw new IllegalStateException(
                        "Duplicate InboxEffect for type '" + effect.effectType()
                                + "': " + clash.getClass().getName()
                                + " and " + effect.getClass().getName());
            }
        }
        this.byType = Map.copyOf(index);
        log.info("InboxEffectRegistry: {} effect(s) registered: {}",
                byType.size(), byType.keySet());
    }

    /**
     * Runs the effect declared by {@code item}, if any.
     *
     * <p>Never throws: a failing effect must not roll back a decision the
     * human already made. The caller records the failure on the item.
     *
     * @return {@code true} when an effect ran to completion, {@code false}
     *         when none applied, and {@code null}-free otherwise
     * @throws InboxEffectFailedException when the effect itself threw —
     *         the caller turns this into a visible failure marker rather
     *         than letting it escape to the answering client
     */
    public boolean dispatch(InboxItemDocument item, AnswerPayload answer) {
        String type = item.getEffectType();
        if (StringUtils.isBlank(type)) {
            return false;
        }
        InboxEffect effect = byType.get(type);
        if (effect == null) {
            // Fail-closed: an item may outlive the release that knew its
            // effect. Refusing beats guessing.
            log.warn("Inbox item '{}' declares unknown effect type '{}' — no effect run",
                    item.getId(), type);
            return false;
        }
        if (answer.getOutcome() != AnswerOutcome.DECIDED) {
            log.info("Inbox item '{}' answered {} — abstention is not consent, no effect run",
                    item.getId(), answer.getOutcome());
            return false;
        }
        if (item.getType() != InboxItemType.APPROVAL) {
            log.warn("Inbox item '{}' has effect type '{}' but item type {} carries no "
                            + "approve/reject answer — no effect run",
                    item.getId(), type, item.getType());
            return false;
        }
        boolean approved = readApproved(answer);
        try {
            if (approved) {
                effect.onApproved(item, answer);
            } else {
                effect.onRejected(item, answer);
            }
        } catch (RuntimeException e) {
            throw new InboxEffectFailedException(
                    "Inbox effect '" + type + "' failed for item '" + item.getId() + "'", e);
        }
        log.info("Inbox effect '{}' ran for item '{}' — approved={} by='{}'",
                type, item.getId(), approved, answer.getAnsweredBy());
        return true;
    }

    /**
     * Server-rendered facts about the pending effect, for the deciding
     * UI. Empty when the item declares no effect, the type is unknown, or
     * the effect has nothing to show.
     */
    public Optional<EffectDescription> describe(InboxItemDocument item) {
        String type = item.getEffectType();
        if (StringUtils.isBlank(type)) {
            return Optional.empty();
        }
        InboxEffect effect = byType.get(type);
        if (effect == null) {
            return Optional.empty();
        }
        try {
            return effect.describe(item);
        } catch (RuntimeException e) {
            // Describing is a read for display — it must never break the
            // inbox listing.
            log.warn("Inbox effect '{}' failed to describe item '{}': {}",
                    type, item.getId(), e.toString());
            return Optional.empty();
        }
    }

    /**
     * Approval flag out of an APPROVAL answer — {@code {"approved":
     * <bool>}}. Anything else (missing, null, non-boolean) reads as
     * {@code false}: a malformed yes is a no.
     */
    private static boolean readApproved(AnswerPayload answer) {
        Map<String, Object> value = answer.getValue();
        if (value == null) {
            return false;
        }
        return value.get("approved") instanceof Boolean b && b;
    }

    /** Thrown when an {@link InboxEffect} implementation fails. */
    public static class InboxEffectFailedException extends RuntimeException {
        public InboxEffectFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

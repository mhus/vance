package de.mhus.vance.brain.wonko;

import de.mhus.vance.api.question.QuestionAnswerDto;
import de.mhus.vance.api.question.QuestionDto;
import de.mhus.vance.api.question.QuestionKind;
import de.mhus.vance.api.question.QuestionOutcome;
import de.mhus.vance.api.question.QuestionSetDto;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Folds the answers to one question set into the register (plan §4.1 step 6,
 * §8.4). Partial answers are valid: a question without an answer counts as
 * {@link QuestionOutcome#LATER}. Mandate proposals are confirmed only by the
 * explicit yes option; everything else leaves the mandate as it was.
 */
final class WonkoAnswerApplier {

    /** Label of the option that confirms a mandate proposal. */
    static final String PROPOSAL_YES = "Yes";

    static final String PROPOSAL_NO = "No";

    private WonkoAnswerApplier() {}

    static WonkoState.RoundStat apply(
            WonkoRegister register,
            QuestionSetDto set,
            List<QuestionAnswerDto> answers,
            List<WonkoState.PendingProposal> proposals,
            int round) {
        Map<String, QuestionAnswerDto> byKey = new HashMap<>();
        for (QuestionAnswerDto a : answers) {
            byKey.putIfAbsent(WonkoKeys.topicKey(a.getKey()), a);
        }
        WonkoState.RoundStat stat = new WonkoState.RoundStat();
        stat.setRound(set.getRound());
        for (QuestionDto q : set.getQuestions()) {
            QuestionAnswerDto answer = byKey.get(q.getKey());
            QuestionOutcome outcome = answer == null ? QuestionOutcome.LATER : answer.getOutcome();
            WonkoState.PendingProposal proposal = proposals.stream()
                    .filter(p -> p.getKey().equals(q.getKey()))
                    .findFirst()
                    .orElse(null);
            if (proposal != null) {
                if (outcome == QuestionOutcome.ANSWERED
                        && answer != null
                        && PROPOSAL_YES.equalsIgnoreCase(
                                String.valueOf(answer.getValue()).trim())
                        && proposal.getChange() != null) {
                    WonkoMandateRules.confirm(register, proposal.getChange(), round);
                }
                count(stat, outcome);
                continue;
            }
            register.topic(q.getKey()).ifPresent(topic -> applyToTopic(topic, q, answer, outcome, round));
            count(stat, outcome);
        }
        stat.setAsked(set.getQuestions().size());
        return stat;
    }

    private static void applyToTopic(
            WonkoTopic topic, QuestionDto question, QuestionAnswerDto answer, QuestionOutcome outcome, int round) {
        switch (outcome) {
            case ANSWERED -> {
                String value = answer == null ? null : answer.getValue();
                if ((value == null || value.isBlank()) && question.getKind() == QuestionKind.CONFIRM) {
                    // An empty answer to a confirm question is the confirmation.
                    value = question.getProposal();
                }
                topic.setStatus(WonkoTopicStatus.ANSWERED);
                topic.setAnswer(value);
                topic.setAnsweredRound(round);
            }
            case SETTLED -> {
                topic.setStatus(WonkoTopicStatus.SETTLED);
                topic.setAnsweredRound(round);
            }
            case LATER -> topic.setStatus(WonkoTopicStatus.LATER);
            case UNKNOWN -> {
                topic.setStatus(WonkoTopicStatus.UNKNOWN);
                topic.setAnsweredRound(round);
            }
        }
    }

    private static void count(WonkoState.RoundStat stat, QuestionOutcome outcome) {
        switch (outcome) {
            case ANSWERED -> stat.setAnswered(stat.getAnswered() + 1);
            case SETTLED -> stat.setSettled(stat.getSettled() + 1);
            case LATER -> stat.setLater(stat.getLater() + 1);
            case UNKNOWN -> stat.setUnknown(stat.getUnknown() + 1);
        }
    }
}

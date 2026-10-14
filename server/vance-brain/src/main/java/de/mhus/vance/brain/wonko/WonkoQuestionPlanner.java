package de.mhus.vance.brain.wonko;

import de.mhus.vance.api.question.QuestionDto;
import de.mhus.vance.api.question.QuestionKind;
import de.mhus.vance.api.question.QuestionOptionDto;
import de.mhus.vance.api.question.QuestionWeight;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Turns the findings of one check round into register updates and the
 * questions of the round (plan §4.1 steps 3–4). Pure logic over the register:
 *
 * <ul>
 *   <li>findings under a settled topic are dropped — "good as it is" closes
 *       the whole branch;</li>
 *   <li>a topic the user put off ("later") is not asked again in the same run;</li>
 *   <li>answered topics come back only as a new contradiction, once; a second
 *       time they become {@link WonkoTopicStatus#CONFLICT} and are reported,
 *       not asked;</li>
 *   <li>the mandate decides per category: decide (with a proposal) records a
 *       decision, confirm asks for a yes, ask asks openly — blockers and
 *       contradictions to the user's own statements are at least
 *       confirmed;</li>
 *   <li>a question whose root is still open waits for the root;</li>
 *   <li>blockers first, then by the model's order; minor findings are only
 *       reported.</li>
 * </ul>
 */
final class WonkoQuestionPlanner {

    /** What one round produced. */
    record Plan(List<QuestionDto> questions, List<WonkoTopic> decided, int suppressed, int deferred) {}

    private WonkoQuestionPlanner() {}

    static Plan plan(WonkoRegister register, List<WonkoFinding> findings, int round, int maxQuestions) {
        return plan(register, findings, round, maxQuestions, Set.of());
    }

    /**
     * @param postponed topic keys the user explicitly put off ("later") in this
     *     run — not asked again before the next run on the subject
     */
    static Plan plan(
            WonkoRegister register, List<WonkoFinding> findings, int round, int maxQuestions, Set<String> postponed) {
        WonkoMandate mandate = register.getMandate();
        List<Candidate> candidates = new ArrayList<>();
        List<WonkoTopic> decided = new ArrayList<>();
        int suppressed = 0;
        int order = 0;
        Set<String> seen = new HashSet<>();
        for (WonkoFinding finding : findings) {
            if (finding.key().isEmpty() || !seen.add(finding.key())) {
                continue;
            }
            if (register.settledAncestor(finding.key()).isPresent()) {
                suppressed++;
                continue;
            }
            WonkoTopic topic = register.topic(finding.key()).orElse(null);
            if (topic != null && !reopens(topic, finding, postponed)) {
                suppressed++;
                continue;
            }
            if (topic == null) {
                topic = new WonkoTopic();
                topic.setKey(finding.key());
                register.getTopics().add(topic);
            } else if (topic.getStatus() == WonkoTopicStatus.ANSWERED) {
                topic.setReaskCount(topic.getReaskCount() + 1);
            }
            boolean wasLater = topic.getStatus() == WonkoTopicStatus.LATER;
            absorb(topic, finding);
            if (finding.weight() == QuestionWeight.MINOR) {
                continue;
            }
            MandateLevel level = mandate.levelFor(topic.getCategory());
            if (finding.weight() == QuestionWeight.BLOCKER || finding.contradictsUser()) {
                level = MandateLevel.stricter(level, MandateLevel.CONFIRM);
            }
            boolean hasProposal =
                    finding.proposal() != null && !finding.proposal().isBlank();
            if (level == MandateLevel.DECIDE && hasProposal) {
                topic.setStatus(WonkoTopicStatus.DECIDED);
                topic.setDecision(finding.proposal());
                topic.setDecisionReason("within mandate (category " + topic.getCategory() + ": decide)"
                        + (finding.context() == null ? "" : " — " + finding.context()));
                topic.setAnsweredRound(round);
                decided.add(topic);
                continue;
            }
            candidates.add(new Candidate(topic, finding, level, hasProposal, wasLater, order++));
        }

        List<Candidate> ready = new ArrayList<>();
        int deferred = 0;
        for (Candidate c : candidates) {
            if (waitsForRoot(register, c.finding(), candidates)) {
                deferred++;
                continue;
            }
            ready.add(c);
        }
        ready.sort(Comparator.comparingInt((Candidate c) -> c.finding().weight().ordinal())
                .thenComparing(Candidate::wasLater)
                .thenComparingInt(Candidate::order));

        List<QuestionDto> questions = new ArrayList<>();
        for (Candidate c : ready) {
            if (questions.size() >= maxQuestions) {
                break;
            }
            questions.add(toQuestion(c));
            c.topic().setStatus(WonkoTopicStatus.ASKED);
            c.topic().setAskedRound(round);
        }
        return new Plan(questions, decided, suppressed, deferred);
    }

    /**
     * Open questions a previous run left behind — found, asked or put off,
     * but never answered. A new run asks them again even when its own checks
     * do not raise them a second time: the register already knows they are
     * open, and a model call that misses them this time does not close them.
     * Minor topics and topics under a settled branch stay out.
     */
    static List<WonkoFinding> carryOver(WonkoRegister register) {
        List<WonkoFinding> out = new ArrayList<>();
        for (WonkoTopic topic : register.getTopics()) {
            boolean open =
                    switch (topic.getStatus()) {
                        case OPEN, ASKED, LATER -> true;
                        default -> false;
                    };
            if (!open
                    || topic.getWeight() == QuestionWeight.MINOR
                    || topic.getQuestion() == null
                    || topic.getQuestion().isBlank()
                    || register.settledAncestor(topic.getKey()).isPresent()) {
                continue;
            }
            out.add(new WonkoFinding(
                    topic.getKey(),
                    topic.getTitle(),
                    topic.getCategory(),
                    topic.getAnchor(),
                    topic.getConcern(),
                    topic.getWeight(),
                    topic.getQuestion(),
                    QuestionKind.TEXT,
                    List.of(),
                    topic.getProposal(),
                    null,
                    null,
                    false,
                    topic.getFindingReason(),
                    topic.getEvidenceRef(),
                    topic.getEvidenceQuote()));
        }
        return out;
    }

    /**
     * Whether a finding may reopen a topic the register already knows.
     * Open/later topics simply continue; answered ones only on a new
     * contradiction and only once — the second time the topic turns into a
     * reported conflict.
     */
    private static boolean reopens(WonkoTopic topic, WonkoFinding finding, Set<String> postponed) {
        return switch (topic.getStatus()) {
            case OPEN, ASKED -> true;
            case LATER -> !postponed.contains(topic.getKey());
            case ANSWERED -> {
                if (!finding.isContradiction()) {
                    yield false;
                }
                if (topic.getReaskCount() >= 1) {
                    topic.setStatus(WonkoTopicStatus.CONFLICT);
                    topic.setDecisionReason("contradiction raised again after the answer — "
                            + (finding.reason() == null ? "no further reason given" : finding.reason()));
                    yield false;
                }
                yield true;
            }
            case SETTLED, DECIDED, UNKNOWN, CONFLICT -> false;
            case UNCLEAR -> true;
        };
    }

    private static void absorb(WonkoTopic topic, WonkoFinding finding) {
        topic.setTitle(finding.title().isBlank() ? finding.key() : finding.title());
        if (finding.category() != null && !finding.category().isBlank()) {
            topic.setCategory(finding.category());
        }
        topic.setAnchor(finding.anchor());
        topic.setConcern(finding.concern());
        topic.setWeight(finding.weight());
        topic.setQuestion(finding.question());
        topic.setProposal(finding.proposal());
        topic.setFindingReason(finding.reason());
        topic.setEvidenceRef(finding.evidenceRef());
        topic.setEvidenceQuote(finding.evidenceQuote());
        topic.setStatus(WonkoTopicStatus.OPEN);
    }

    /**
     * A finding whose root ({@code dependsOn}) is still unresolved waits: the
     * answer to the root often makes it moot (§4.4). A root that is unknown to
     * the register and not among this round's findings does not block.
     */
    private static boolean waitsForRoot(WonkoRegister register, WonkoFinding finding, List<Candidate> candidates) {
        String root = finding.dependsOn();
        if (root == null || root.isEmpty() || root.equals(finding.key())) {
            return false;
        }
        boolean rootIsCandidate =
                candidates.stream().anyMatch(c -> c.topic().getKey().equals(root));
        if (rootIsCandidate) {
            return true;
        }
        return register.topic(root)
                .map(t -> t.getStatus() == WonkoTopicStatus.OPEN
                        || t.getStatus() == WonkoTopicStatus.ASKED
                        || t.getStatus() == WonkoTopicStatus.LATER)
                .orElse(false);
    }

    private static QuestionDto toQuestion(Candidate c) {
        WonkoFinding f = c.finding();
        QuestionKind kind;
        @Nullable String proposal = null;
        if (c.level() == MandateLevel.ASK || !c.hasProposal()) {
            // An open question: a confirm-shaped finding without a usable
            // proposal (or under an ask category) is asked as free text.
            kind = f.kind() == QuestionKind.CHOICE && !f.options().isEmpty() ? QuestionKind.CHOICE : QuestionKind.TEXT;
        } else {
            kind = QuestionKind.CONFIRM;
            proposal = f.proposal();
        }
        List<QuestionOptionDto> options = kind == QuestionKind.CHOICE
                ? f.options().stream()
                        .map(o -> QuestionOptionDto.builder().label(o).build())
                        .toList()
                : List.of();
        return QuestionDto.builder()
                .key(f.key())
                .text(f.question().isBlank() ? f.title() : f.question())
                .kind(kind)
                .options(new ArrayList<>(options))
                .proposal(proposal)
                .context(f.context() != null ? f.context() : f.anchor())
                .weight(f.weight())
                .build();
    }

    private record Candidate(
            WonkoTopic topic,
            WonkoFinding finding,
            MandateLevel level,
            boolean hasProposal,
            boolean wasLater,
            int order) {}
}

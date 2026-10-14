package de.mhus.vance.brain.wonko;

import de.mhus.vance.api.question.QuestionWeight;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Renders the register as the Markdown result report (plan §5.5) — the same
 * renderer serves the end-of-run report and an export on request.
 */
final class WonkoReportRenderer {

    private WonkoReportRenderer() {}

    static String render(WonkoRegister register, @Nullable String endNote) {
        return render(register, endNote, null);
    }

    /**
     * The report with the run's view: claims checked, references used, and
     * every logged decision with its reason (plan §4a.8) when {@code run} is
     * given.
     */
    static String render(WonkoRegister register, @Nullable String endNote, @Nullable WonkoState run) {
        List<WonkoTopic> topics = register.getTopics();
        StringBuilder sb = new StringBuilder();
        sb.append("# Review: ")
                .append(String.join(", ", register.getSubjectRefs()))
                .append('\n');
        sb.append("Round ")
                .append(register.getRounds())
                .append(" · ")
                .append(count(topics, WonkoTopicStatus.ANSWERED) + count(topics, WonkoTopicStatus.SETTLED))
                .append(" clarified · ")
                .append(count(topics, WonkoTopicStatus.DECIDED))
                .append(" decided · ")
                .append(openTopics(topics).size())
                .append(" open\n");
        if (endNote != null && !endNote.isBlank()) {
            sb.append('\n').append(endNote).append('\n');
        }
        if (run != null && !run.getReferencePaths().isEmpty()) {
            sb.append('\n')
                    .append(run.getClaims().size())
                    .append(" claim(s) checked against ")
                    .append(run.getReferencePaths().size())
                    .append(" reference document(s), ")
                    .append(run.getPassages().size())
                    .append(" applicable passage(s), ")
                    .append(run.getCalls())
                    .append(" model call(s).\n");
        }

        section(
                sb,
                "Clarified",
                topics.stream()
                        .filter(t ->
                                t.getStatus() == WonkoTopicStatus.ANSWERED || t.getStatus() == WonkoTopicStatus.SETTLED)
                        .map(t -> "**" + t.getTitle() + "** — "
                                + (t.getStatus() == WonkoTopicStatus.SETTLED ? "good as it is." : nz(t.getAnswer()))
                                + evidence(t))
                        .toList());
        section(
                sb,
                "Decided by me",
                topics.stream()
                        .filter(t -> t.getStatus() == WonkoTopicStatus.DECIDED)
                        .map(t -> "**" + t.getTitle() + "** → " + nz(t.getDecision()) + ". Reason: "
                                + nz(t.getDecisionReason()))
                        .toList());
        section(
                sb,
                "Open",
                openTopics(topics).stream()
                        .map(t -> "**" + t.getTitle() + "** — " + statusLabel(t.getStatus())
                                + (t.getQuestion() == null ? "" : ": " + t.getQuestion())
                                + evidence(t))
                        .toList());
        section(
                sb,
                "Unclear",
                topics.stream()
                        .filter(t -> t.getStatus() == WonkoTopicStatus.UNCLEAR)
                        .map(t -> "**" + t.getTitle() + "** — could not be determined: " + nz(t.getFindingReason()))
                        .toList());
        section(
                sb,
                "Unresolved conflicts",
                topics.stream()
                        .filter(t -> t.getStatus() == WonkoTopicStatus.CONFLICT)
                        .map(t -> "**" + t.getTitle() + "** — came back as a contradiction after it was answered: "
                                + nz(t.getAnswer())
                                + (t.getDecisionReason() == null ? "" : " (" + t.getDecisionReason() + ")"))
                        .toList());
        section(
                sb,
                "Minor notes",
                topics.stream()
                        .filter(t -> t.getStatus() == WonkoTopicStatus.OPEN && t.getWeight() == QuestionWeight.MINOR)
                        .map(t -> "**" + t.getTitle() + "**" + (t.getQuestion() == null ? "" : " — " + t.getQuestion()))
                        .toList());

        if (run != null) {
            section(
                    sb,
                    "Decisions along the way",
                    run.getDecisions().stream()
                            .map(d -> d.getSubject() + " — " + d.getOutcome() + ": " + d.getReason())
                            .toList());
        }

        Map<String, WonkoCategory> categories = register.getMandate().getCategories();
        sb.append("\n## Mandate\n");
        sb.append("Default: ")
                .append(level(register.getMandate().getDefaultLevel()))
                .append('\n');
        if (!categories.isEmpty()) {
            sb.append("\n| Category | Level | Limit |\n|---|---|---|\n");
            categories.forEach((name, c) -> sb.append("| ")
                    .append(name)
                    .append(c.isCreatedByWonko() ? " (new)" : "")
                    .append(" | ")
                    .append(level(c.getLevel()))
                    .append(" | ")
                    .append(c.isLocked() ? "locked" : c.getCeiling() == null ? "" : "up to " + level(c.getCeiling()))
                    .append(" |\n"));
        }
        if (!register.getMandateLog().isEmpty()) {
            sb.append("\nChanges:\n");
            for (WonkoMandateChange change : register.getMandateLog()) {
                sb.append("- round ")
                        .append(change.getRound())
                        .append(", ")
                        .append(change.getBy())
                        .append(": ")
                        .append(change.getChange())
                        .append(change.getReason() == null ? "" : " — " + change.getReason())
                        .append('\n');
            }
        }
        return sb.toString();
    }

    static List<WonkoTopic> openTopics(List<WonkoTopic> topics) {
        return topics.stream()
                .filter(t -> t.getWeight() != QuestionWeight.MINOR)
                .filter(t -> t.getStatus() == WonkoTopicStatus.OPEN
                        || t.getStatus() == WonkoTopicStatus.ASKED
                        || t.getStatus() == WonkoTopicStatus.LATER
                        || t.getStatus() == WonkoTopicStatus.UNKNOWN)
                .toList();
    }

    private static void section(StringBuilder sb, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        sb.append("\n## ").append(title).append('\n');
        for (String line : lines) {
            sb.append("- ").append(line).append('\n');
        }
    }

    private static long count(List<WonkoTopic> topics, WonkoTopicStatus status) {
        return topics.stream().filter(t -> t.getStatus() == status).count();
    }

    private static String statusLabel(WonkoTopicStatus status) {
        return switch (status) {
            case LATER -> "later";
            case UNKNOWN -> "unknown to the user";
            default -> "not asked yet";
        };
    }

    private static String level(MandateLevel level) {
        return level.name().toLowerCase(Locale.ROOT);
    }

    /** The evidence a finding rests on, when it was checked against a reference. */
    private static String evidence(WonkoTopic t) {
        if (t.getEvidenceRef() == null) {
            return "";
        }
        return " (" + t.getEvidenceRef()
                + (t.getEvidenceQuote() == null ? "" : ": \u201C" + t.getEvidenceQuote() + "\u201D") + ")";
    }

    private static String nz(@Nullable String s) {
        return s == null ? "" : s;
    }
}

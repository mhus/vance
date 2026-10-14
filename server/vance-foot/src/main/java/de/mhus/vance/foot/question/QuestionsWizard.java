package de.mhus.vance.foot.question;

import de.mhus.vance.api.question.QuestionAnswerDto;
import de.mhus.vance.api.question.QuestionDto;
import de.mhus.vance.api.question.QuestionKind;
import de.mhus.vance.api.question.QuestionOutcome;
import de.mhus.vance.api.question.QuestionSetAnswerDto;
import de.mhus.vance.api.question.QuestionSetDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Answers a question set line by line — the CLI form of the web wizard, no
 * terminal in here. Each input line is one step:
 *
 * <ul>
 *   <li>a number picks an option, Enter confirms the asker's assumption,
 *       any other text is the answer;</li>
 *   <li>{@code :ok} good as it is, {@code :later}, {@code :unknown},
 *       {@code :back}, {@code :pause} (leave the wizard, the set stays
 *       open);</li>
 *   <li>after the last question an overview: Enter sends, a number reopens
 *       that question.</li>
 * </ul>
 *
 * A single question (an engine's ASK_USER) is answered and sent in one step.
 */
public final class QuestionsWizard {

    /** What the client sends: the readable text and the typed answers. */
    public record Send(String content, QuestionSetAnswerDto answers) {}

    /** The result of one input line: what to print, and whether to send or pause. */
    public record Step(List<String> lines, @Nullable Send send, boolean paused) {

        static Step show(List<String> lines) {
            return new Step(lines, null, false);
        }
    }

    private final QuestionSetDto set;
    private final String setId;
    private final QuestionsAnswers.Labels labels;
    private final Map<String, QuestionAnswerDto> answers = new LinkedHashMap<>();
    private int page;

    public QuestionsWizard(QuestionSetDto set, String setId, QuestionsAnswers.Labels labels) {
        this.set = set;
        this.setId = setId;
        this.labels = labels;
    }

    public String setId() {
        return setId;
    }

    public boolean single() {
        return set.getQuestions().size() == 1;
    }

    private int total() {
        return set.getQuestions().size();
    }

    /** The lines that open the wizard: how to answer, then the current page. */
    public List<String> start() {
        List<String> lines = new ArrayList<>();
        lines.add(
                single()
                        ? "Answer below — a number picks an option, any text is your answer"
                                + " (:ok :later :unknown :pause)."
                        : "Answer below, one question at a time — a number picks an option, Enter confirms the"
                                + " assumption, any text is your answer (:ok :later :unknown :back :pause).");
        lines.addAll(pageLines());
        return lines;
    }

    /** Label for the input line: where in the set the user is. */
    public String promptLabel() {
        if (single()) {
            return "answer › ";
        }
        return page >= total() ? "send? › " : (page + 1) + "/" + total() + " › ";
    }

    public Step offer(String rawLine) {
        String line = rawLine.strip();
        String command = line.toLowerCase(Locale.ROOT);
        if (":pause".equals(command)) {
            return new Step(List.of("Questions paused — /answer continues."), null, true);
        }
        if (page >= total()) {
            return overview(line, command);
        }
        QuestionDto q = set.getQuestions().get(page);
        switch (command) {
            case ":ok" -> {
                return answer(q, QuestionOutcome.SETTLED, null);
            }
            case ":later" -> {
                return answer(q, QuestionOutcome.LATER, null);
            }
            case ":unknown" -> {
                return answer(q, QuestionOutcome.UNKNOWN, null);
            }
            case ":back" -> {
                if (page > 0) {
                    page--;
                }
                return Step.show(pageLines());
            }
            default -> {
                // fall through to the answer forms below
            }
        }
        if (command.startsWith(":")) {
            return Step.show(List.of("Unknown command " + line + " — :ok :later :unknown :back :pause"));
        }
        if (line.isEmpty()) {
            if (q.getKind() == QuestionKind.CONFIRM && q.getProposal() != null) {
                return answer(q, QuestionOutcome.ANSWERED, q.getProposal());
            }
            if (single()) {
                return Step.show(List.of("Type an answer, pick a number, or :pause."));
            }
            // Skipped — the question stays open and is sent as "later".
            page++;
            return Step.show(pageLines());
        }
        Integer pick = number(line, q.getOptions().size());
        if (pick != null) {
            return answer(
                    q, QuestionOutcome.ANSWERED, q.getOptions().get(pick - 1).getLabel());
        }
        return answer(q, QuestionOutcome.ANSWERED, line);
    }

    private Step overview(String line, String command) {
        if (line.isEmpty() || ":send".equals(command)) {
            return send();
        }
        if (":back".equals(command)) {
            page = total() - 1;
            return Step.show(pageLines());
        }
        Integer reopen = number(line, total());
        if (reopen != null) {
            page = reopen - 1;
            return Step.show(pageLines());
        }
        return Step.show(List.of("Enter sends, a number reopens that question, :back, :pause."));
    }

    private Step answer(QuestionDto q, QuestionOutcome outcome, @Nullable String value) {
        answers.put(
                q.getKey(),
                QuestionAnswerDto.builder()
                        .key(q.getKey())
                        .outcome(outcome)
                        .value(value)
                        .build());
        if (single()) {
            return send();
        }
        page++;
        return Step.show(pageLines());
    }

    private Step send() {
        return new Step(
                List.of(),
                new Send(
                        QuestionsAnswers.readable(set, answers, labels),
                        QuestionsAnswers.toSetAnswer(setId, set, answers)),
                false);
    }

    /** The current page: a question with its options, or the overview. */
    List<String> pageLines() {
        List<String> lines = new ArrayList<>();
        if (page >= total()) {
            lines.add("Overview — Enter sends, a number reopens that question:");
            for (int i = 0; i < total(); i++) {
                QuestionDto q = set.getQuestions().get(i);
                lines.add("  " + (i + 1) + ". " + firstLine(q.getText()) + " → "
                        + QuestionsAnswers.answerLabel(answers.get(q.getKey()), labels));
            }
            return lines;
        }
        QuestionDto q = set.getQuestions().get(page);
        String head = single() ? "" : "(" + (page + 1) + "/" + total() + ") ";
        lines.add(head + firstLine(q.getText()));
        if (!q.getOptions().isEmpty()) {
            StringBuilder sb = new StringBuilder("  ");
            for (int i = 0; i < q.getOptions().size(); i++) {
                if (i > 0) {
                    sb.append("   ");
                }
                sb.append('[')
                        .append(i + 1)
                        .append("] ")
                        .append(q.getOptions().get(i).getLabel());
            }
            lines.add(sb.toString());
        }
        if (q.getKind() == QuestionKind.CONFIRM && q.getProposal() != null) {
            lines.add("  Enter = confirm: " + q.getProposal());
        }
        QuestionAnswerDto given = answers.get(q.getKey());
        if (given != null) {
            lines.add("  current answer: " + QuestionsAnswers.answerLabel(given, labels));
        }
        return lines;
    }

    private static @Nullable Integer number(String line, int max) {
        if (!line.matches("\\d{1,3}")) {
            return null;
        }
        int n = Integer.parseInt(line);
        return n >= 1 && n <= max ? n : null;
    }

    private static String firstLine(String text) {
        String stripped = text.strip();
        int nl = stripped.indexOf('\n');
        String first = nl < 0 ? stripped : stripped.substring(0, nl).strip() + " …";
        return first.length() > 160 ? first.substring(0, 157) + "…" : first;
    }
}

package de.mhus.vance.foot.question;

import de.mhus.vance.api.question.QuestionDto;
import de.mhus.vance.api.question.QuestionKind;
import de.mhus.vance.api.question.QuestionOptionDto;
import de.mhus.vance.api.question.QuestionSetDto;
import de.mhus.vance.api.question.QuestionWeight;
import java.util.List;

/**
 * A question set as readable Markdown for the terminal: intro, the questions
 * numbered (a single one — an engine's ASK_USER — as itself), evidence as a
 * quote, the asker's assumption, the options with the number that picks them.
 * Fences that do not parse stay as they are — the user still sees the text.
 */
public final class QuestionsText {

    private QuestionsText() {}

    /** {@code content} with every valid {@code questions} fence replaced by its readable form. */
    public static String replaceFences(String content) {
        List<QuestionsFence.Found> fences = QuestionsFence.find(content);
        if (fences.isEmpty()) {
            return content;
        }
        StringBuilder out = new StringBuilder();
        int pos = 0;
        for (QuestionsFence.Found fence : fences) {
            out.append(content, pos, fence.start());
            try {
                out.append(render(QuestionsFence.parse(fence.body())));
            } catch (QuestionsFenceException e) {
                out.append(content, fence.start(), fence.end());
            }
            pos = fence.end();
        }
        out.append(content.substring(pos));
        return out.toString();
    }

    public static String render(QuestionSetDto set) {
        StringBuilder sb = new StringBuilder();
        if (set.getIntro() != null && !set.getIntro().isBlank()) {
            sb.append(set.getIntro().strip()).append("\n\n");
        }
        List<QuestionDto> questions = set.getQuestions();
        for (int i = 0; i < questions.size(); i++) {
            QuestionDto q = questions.get(i);
            if (questions.size() > 1) {
                sb.append("**").append(i + 1).append(".** ");
            }
            if (q.getWeight() == QuestionWeight.BLOCKER) {
                sb.append("**[blocker]** ");
            }
            sb.append(q.getText().strip()).append('\n');
            if (q.getContext() != null) {
                sb.append("\n> ")
                        .append(q.getContext().strip().replace("\n", "\n> "))
                        .append('\n');
            }
            if (q.getKind() == QuestionKind.CONFIRM && q.getProposal() != null) {
                sb.append("\n*Assumption:* ").append(q.getProposal().strip()).append('\n');
            }
            if (!q.getOptions().isEmpty()) {
                sb.append('\n').append(options(q.getOptions())).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** The options as one line, each with the number that picks it. */
    static String options(List<QuestionOptionDto> options) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < options.size(); i++) {
            QuestionOptionDto o = options.get(i);
            if (i > 0) {
                sb.append("   ");
            }
            sb.append('`').append('[').append(i + 1).append("]` ").append(o.getLabel());
            if (o.getDescription() != null) {
                sb.append(" — ").append(o.getDescription());
            }
        }
        return sb.toString();
    }
}

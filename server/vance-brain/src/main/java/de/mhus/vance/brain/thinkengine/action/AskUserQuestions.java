package de.mhus.vance.brain.thinkengine.action;

import de.mhus.vance.api.question.QuestionDto;
import de.mhus.vance.api.question.QuestionKind;
import de.mhus.vance.api.question.QuestionOptionDto;
import de.mhus.vance.api.question.QuestionSetDto;
import de.mhus.vance.shared.document.kind.QuestionsCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The chat form of an ASK_USER action (spec doc-kind-questions §6). With
 * options the question goes out as a {@code questions} fence — one question,
 * the options as its choices — built from the action in code, so it is valid
 * by construction, every client renders it with the one questions renderer,
 * and a parent that relays the child's reply carries it along as text.
 * Without options it stays a plain question the user answers in free text.
 */
public final class AskUserQuestions {

    /** Key of the single question — the answer reaches the engine as readable text anyway. */
    static final String KEY = "answer";

    private AskUserQuestions() {}

    public static String render(String message, @Nullable Object optionsRaw) {
        List<QuestionOptionDto> options = options(optionsRaw);
        if (options.isEmpty() || message.isBlank()) {
            return message;
        }
        QuestionDto question = QuestionDto.builder()
                .key(KEY)
                .text(message.strip())
                // A choice needs two options; a single one is offered on a free-text question.
                .kind(options.size() >= 2 ? QuestionKind.CHOICE : QuestionKind.TEXT)
                .options(new ArrayList<>(options))
                .build();
        // No id: the engine reads the answer as text, nothing marks a reply as
        // "the answer to this set" — any later user message closes it.
        return QuestionsCodec.toFence(QuestionSetDto.builder()
                .id("")
                .questions(new ArrayList<>(List.of(question)))
                .build());
    }

    private static List<QuestionOptionDto> options(@Nullable Object raw) {
        List<QuestionOptionDto> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m) || !(m.get("label") instanceof String label) || label.isBlank()) {
                continue;
            }
            String description = m.get("description") instanceof String d && !d.isBlank() ? d.strip() : null;
            out.add(QuestionOptionDto.builder()
                    .label(label.strip())
                    .description(description)
                    .build());
        }
        return out;
    }
}

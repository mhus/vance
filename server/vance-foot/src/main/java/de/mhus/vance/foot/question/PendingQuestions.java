package de.mhus.vance.foot.question;

import de.mhus.vance.api.question.QuestionSetDto;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.LiveRegion;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * The open question set of the conversation and its line wizard. An assistant
 * message of the main process carrying a {@code questions} fence opens it;
 * while it is active the input line answers it (slash commands still work).
 * {@code :pause} steps out — the set stays open and {@code /answer}
 * continues it.
 *
 * <p>The set is answered when this client sends the answers, when a user
 * message is marked as its answer ({@code meta.questionsAnswered} = its id),
 * or — for a set without an id — on any later user message; a newer set
 * replaces it. Same rules as the web chat (doc-kind-questions §4).
 *
 * <p>Thread-safety: the socket handler presents and closes, the input thread
 * offers lines — the state is one {@link AtomicReference}.
 */
@Component
public class PendingQuestions {

    /** Meta key on a user message that answered a set (server: ChatMessageDocument). */
    public static final String META_QUESTIONS_ANSWERED = "questionsAnswered";

    private record Open(QuestionsWizard wizard, boolean hasId, boolean paused) {}

    private final ChatTerminal terminal;
    private final LiveRegion liveRegion;
    private final AtomicReference<@Nullable Open> open = new AtomicReference<>();

    public PendingQuestions(ChatTerminal terminal, @Lazy LiveRegion liveRegion) {
        this.terminal = terminal;
        this.liveRegion = liveRegion;
    }

    /** True while typed lines answer the open set. */
    public boolean isActive() {
        Open o = open.get();
        return o != null && !o.paused();
    }

    /** An assistant message of the main process: the last valid fence in it opens a set. */
    public void onAssistantMessage(@Nullable String content) {
        if (content == null) {
            return;
        }
        List<QuestionsFence.Found> fences = QuestionsFence.find(content);
        for (int i = fences.size() - 1; i >= 0; i--) {
            String body = fences.get(i).body();
            QuestionSetDto set;
            try {
                set = QuestionsFence.parse(body);
            } catch (QuestionsFenceException e) {
                continue;
            }
            QuestionsWizard wizard =
                    new QuestionsWizard(set, QuestionsAnswers.setIdFor(set, body), QuestionsAnswers.Labels.ENGLISH);
            open.set(new Open(wizard, set.getId() != null && !set.getId().isBlank(), false));
            print(wizard.start());
            liveRegion.setPromptLabel(wizard.promptLabel());
            return;
        }
    }

    /** A user message: it closes the set when it answered it (see class doc). */
    public void onUserMessage(@Nullable Map<String, Object> meta) {
        Open o = open.get();
        if (o == null) {
            return;
        }
        Object answered = meta == null ? null : meta.get(META_QUESTIONS_ANSWERED);
        if (!o.hasId() || o.wizard().setId().equals(answered)) {
            close();
        }
    }

    /** One typed line for the active set; the caller sends when the step says so. */
    public QuestionsWizard.Step offer(String line) {
        Open o = open.get();
        if (o == null || o.paused()) {
            return QuestionsWizard.Step.show(List.of());
        }
        QuestionsWizard.Step step = o.wizard().offer(line);
        print(step.lines());
        if (step.send() != null) {
            close();
        } else if (step.paused()) {
            open.set(new Open(o.wizard(), o.hasId(), true));
            liveRegion.setPromptLabel(null);
        } else {
            liveRegion.setPromptLabel(o.wizard().promptLabel());
        }
        return step;
    }

    /** {@code /answer}: continue a paused set. */
    public boolean resume() {
        Open o = open.get();
        if (o == null) {
            return false;
        }
        open.set(new Open(o.wizard(), o.hasId(), false));
        print(o.wizard().pageLines());
        liveRegion.setPromptLabel(o.wizard().promptLabel());
        return true;
    }

    public void close() {
        if (open.getAndSet(null) != null) {
            liveRegion.setPromptLabel(null);
        }
    }

    private void print(List<String> lines) {
        for (String line : lines) {
            terminal.chat(line);
        }
    }
}

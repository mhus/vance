package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * A CLI client is blocked on a question and cannot proceed without an answer.
 *
 * <p>This is the reason the remote-control channel exists: the valuable remote
 * moment is not typing a chat message, it is answering a sandbox permission ask
 * from the road. Answering happens through {@link MessageType#CLIENT_INPUT} —
 * an answer is just an input line, and foot's normal input path already routes
 * it to whatever prompt is waiting. {@link #options} exists so a watcher can
 * render buttons instead of asking the human to remember "3 = deny once".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class RemoteClientPrompt {

    /** Which client is asking. */
    private String clientId;

    /** Kind of prompt: {@code permission}, {@code ask-user}, {@code line}. */
    private String kind;

    /** False when the prompt resolved (answered, timed out) — clears the UI. */
    private boolean open;

    /** The question, already rendered as plain text. */
    private @Nullable String question;

    /** Extra detail — for a permission ask, the path or command at stake. */
    private @Nullable String subject;

    /** Selectable answers, in menu order. Empty for free-text prompts. */
    private @Nullable List<RemoteClientPromptOption> options;

    /** Wall-clock millis left before the prompt denies itself, if bounded. */
    private long timeoutMs;
}

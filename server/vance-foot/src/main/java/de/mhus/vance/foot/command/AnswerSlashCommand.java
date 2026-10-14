package de.mhus.vance.foot.command;

import de.mhus.vance.foot.question.PendingQuestions;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.List;
import org.springframework.stereotype.Component;

/** {@code /answer} — continues the open question set after {@code :pause}. */
@Component
public class AnswerSlashCommand implements SlashCommand {

    private final PendingQuestions questions;
    private final ChatTerminal terminal;

    public AnswerSlashCommand(PendingQuestions questions, ChatTerminal terminal) {
        this.questions = questions;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "answer";
    }

    @Override
    public String description() {
        return "Continue answering the open questions (after :pause).";
    }

    @Override
    public void execute(List<String> args) {
        if (!questions.resume()) {
            terminal.info("No open questions.");
        }
    }
}

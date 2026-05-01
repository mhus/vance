package de.mhus.vance.foot.command;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /stop} — request the brain to stop the active chat-process.
 * Same effect as pressing ESC at the prompt: dispatches a
 * {@code process-stop} for the session's active process.
 */
@Component
public class StopCommand implements SlashCommand {

    private final ChatInputService chatInput;

    public StopCommand(ChatInputService chatInput) {
        this.chatInput = chatInput;
    }

    @Override
    public String name() {
        return "stop";
    }

    @Override
    public String description() {
        return "Stop the active chat-process. Equivalent to pressing ESC.";
    }

    @Override
    public void execute(List<String> args) {
        chatInput.requestStop();
    }
}

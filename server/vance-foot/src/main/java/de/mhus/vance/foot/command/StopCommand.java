package de.mhus.vance.foot.command;

import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@code /stop} — close (terminate) active workers in the bound
 * session. Workers transition to {@code CLOSED} with
 * {@code closeReason=STOPPED}; the chat-process keeps going.
 *
 * <p>Symmetric counterpart to {@code /pause}: same target set, but
 * "abandon this direction" instead of "I want to redirect". Arthur
 * sees the STOPPED parent-notifications and decides whether to
 * spawn something new.
 *
 * <p>{@code @Lazy} on {@link ChatInputService} breaks the bean
 * cycle ChatInputService → CommandService → StopCommand →
 * ChatInputService.
 */
@Component
public class StopCommand implements SlashCommand {

    private final ChatInputService chatInput;

    public StopCommand(@Lazy ChatInputService chatInput) {
        this.chatInput = chatInput;
    }

    @Override
    public String name() {
        return "stop";
    }

    @Override
    public String description() {
        return "Close active workers — chat keeps going. Harder counterpart to /pause.";
    }

    @Override
    public void execute(List<String> args) {
        chatInput.requestStop();
    }
}

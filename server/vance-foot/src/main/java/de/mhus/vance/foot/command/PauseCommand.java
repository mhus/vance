package de.mhus.vance.foot.command;

import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@code /pause} — pause active workers in the bound session so the
 * user can clarify. Workers transition to {@code PAUSED}; the
 * chat-process keeps going. Equivalent to pressing ESC at the prompt.
 *
 * <p>{@code @Lazy} on the {@link ChatInputService} dependency breaks
 * the bean-graph cycle (ChatInputService → CommandService →
 * PauseCommand → ChatInputService). The cycle is genuine — slash
 * commands by design know about the chat-input service — but the
 * lazy proxy lets it resolve at first call.
 */
@Component
public class PauseCommand implements SlashCommand {

    private final ChatInputService chatInput;

    public PauseCommand(@Lazy ChatInputService chatInput) {
        this.chatInput = chatInput;
    }

    @Override
    public String name() {
        return "pause";
    }

    @Override
    public String description() {
        return "Pause active workers — chat keeps going. Equivalent to pressing ESC.";
    }

    @Override
    public void execute(List<String> args) {
        chatInput.requestPause();
    }
}

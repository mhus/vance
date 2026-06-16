package de.mhus.vance.foot.connection.handlers;

import de.mhus.vance.api.notification.NotificationDto;
import de.mhus.vance.api.notification.NotificationSeverity;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.MessageHandler;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.StreamingDisplay;
import de.mhus.vance.foot.ui.Verbosity;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders {@link MessageType#NOTIFY} side-channel notifications: ring
 * the terminal bell and emit one severity-colored toast line.
 *
 * <p>Distinct from {@code ProcessProgressHandler} — notifications are
 * always rendered (no verbosity gate), since they exist precisely to
 * grab the user's attention.
 *
 * <p>Spec: {@code specification/user-notification-channel.md}.
 */
@Component
public class NotifyHandler implements MessageHandler {

    private final ChatTerminal terminal;
    private final StreamingDisplay streaming;
    private final ObjectMapper json = JsonMapper.builder().build();

    public NotifyHandler(ChatTerminal terminal, StreamingDisplay streaming) {
        this.terminal = terminal;
        this.streaming = streaming;
    }

    @Override
    public String messageType() {
        return MessageType.NOTIFY;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        NotificationDto dto = json.convertValue(envelope.getData(), NotificationDto.class);
        if (dto == null || dto.getText() == null || dto.getText().isBlank()) {
            return;
        }
        NotificationSeverity severity = dto.getSeverity() == null
                ? NotificationSeverity.INFO
                : dto.getSeverity();

        // Terminate any in-flight chat stream so the toast lands on its
        // own line. The bell rings unconditionally — a stuck stream
        // mustn't swallow the user's attention cue.
        streaming.suspend();
        terminal.bell();
        terminal.printlnStyled(Verbosity.INFO, render(severity, dto));
    }

    private static AttributedString render(NotificationSeverity severity, NotificationDto dto) {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        AttributedStyle headerStyle = headerStyleFor(severity);
        sb.style(headerStyle)
                .append("[NOTIFY · ")
                .append(severity.name())
                .append("]")
                .style(AttributedStyle.DEFAULT)
                .append(' ');
        String src = formatSource(dto);
        if (!src.isEmpty()) {
            sb.style(AttributedStyle.DEFAULT
                            .foreground(AttributedStyle.BRIGHT + AttributedStyle.BLACK))
                    .append(src)
                    .append(" · ")
                    .style(AttributedStyle.DEFAULT);
        }
        sb.append(dto.getText());
        return sb.toAttributedString();
    }

    private static AttributedStyle headerStyleFor(NotificationSeverity severity) {
        return switch (severity) {
            case INFO -> AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold();
            case WARN -> AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold();
            case ERROR -> AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold();
        };
    }

    private static String formatSource(NotificationDto dto) {
        String title = dto.getSourceProcessTitle();
        String name = dto.getSourceProcessName();
        if (title != null && !title.isBlank()) return title;
        if (name != null && !name.isBlank()) return name;
        return "";
    }
}

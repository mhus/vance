package de.mhus.vance.foot.connection.handlers;

import de.mhus.vance.api.thinkprocess.PlanProposedNotification;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.MessageHandler;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.StreamingDisplay;
import de.mhus.vance.foot.ui.Verbosity;
import java.util.ArrayList;
import java.util.List;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders {@code plan-proposed} notifications. Arthur emits this when
 * a {@code PROPOSE_PLAN} action runs.
 *
 * <p>The full plan markdown is delivered via the regular ChatMessage
 * stream — this notification is the metadata banner that lets the UI
 * highlight the plan ("Plan v2", summary line, awaiting-approval cue).
 * Rendered as an ASCII-bordered box so it stands out in the scrollback
 * (see {@code readme/arthur-plan-mode.md} §10).
 */
@Component
public class PlanProposedHandler implements MessageHandler {

    /**
     * Outer box width. Adapts to the terminal width but is clamped:
     * narrow terminals (<48) get a usable minimum, very wide ones
     * (>96) get a readable maximum so the summary line doesn't stretch
     * to absurd lengths.
     */
    private static final int MIN_BOX_WIDTH = 48;
    private static final int MAX_BOX_WIDTH = 96;
    private static final int BOX_MARGIN = 4;

    private static final String APPROVAL_HINT =
            "antworte mit \"ok\"/\"mach so\" für Approval, oder mit Korrekturen";

    private static final AttributedStyle BORDER_STYLE = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.CYAN)
            .bold();

    private static final AttributedStyle CONTENT_STYLE = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.CYAN);

    private static final AttributedStyle DIM_STYLE = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.BRIGHT + AttributedStyle.BLACK);

    private final ChatTerminal terminal;
    private final StreamingDisplay streaming;
    private final ObjectMapper json = JsonMapper.builder().build();

    public PlanProposedHandler(ChatTerminal terminal, StreamingDisplay streaming) {
        this.terminal = terminal;
        this.streaming = streaming;
    }

    @Override
    public String messageType() {
        return MessageType.PLAN_PROPOSED;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        PlanProposedNotification msg = json.convertValue(
                envelope.getData(), PlanProposedNotification.class);
        if (msg == null) return;

        streaming.suspend();
        int width = clampWidth(terminal.width() - BOX_MARGIN);
        // Inner content area: total width minus the two border glyphs
        // and the surrounding spaces ("║ " + " ║").
        int inner = Math.max(8, width - 4);

        String header = "Plan" + (msg.getPlanVersion() > 1 ? " v" + msg.getPlanVersion() : "");
        terminal.printlnStyled(Verbosity.INFO, topLine(header, width));
        if (msg.getSummary() != null && !msg.getSummary().isBlank()) {
            for (String line : wrap(msg.getSummary(), inner)) {
                terminal.printlnStyled(Verbosity.INFO, contentLine(line, inner, CONTENT_STYLE));
            }
            terminal.printlnStyled(Verbosity.INFO, contentLine("", inner, CONTENT_STYLE));
        }
        for (String line : wrap(APPROVAL_HINT, inner)) {
            terminal.printlnStyled(Verbosity.INFO, contentLine(line, inner, DIM_STYLE));
        }
        terminal.printlnStyled(Verbosity.INFO, bottomLine(width));
    }

    private static int clampWidth(int requested) {
        if (requested < MIN_BOX_WIDTH) return MIN_BOX_WIDTH;
        if (requested > MAX_BOX_WIDTH) return MAX_BOX_WIDTH;
        return requested;
    }

    private static AttributedString topLine(String header, int width) {
        // ╔═══ <header> ═...═╗
        String prefix = "╔═══ " + header + " ";
        int fillCount = Math.max(1, width - prefix.length() - 1);
        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.style(BORDER_STYLE);
        sb.append(prefix);
        sb.append("═".repeat(fillCount));
        sb.append("╗");
        return sb.toAttributedString();
    }

    private static AttributedString bottomLine(int width) {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.style(BORDER_STYLE);
        sb.append("╚");
        sb.append("═".repeat(Math.max(0, width - 2)));
        sb.append("╝");
        return sb.toAttributedString();
    }

    private static AttributedString contentLine(String text, int inner, AttributedStyle textStyle) {
        String padded = text.length() >= inner
                ? text.substring(0, inner)
                : text + " ".repeat(inner - text.length());
        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.style(BORDER_STYLE).append("║ ");
        sb.style(textStyle).append(padded);
        sb.style(BORDER_STYLE).append(" ║");
        return sb.toAttributedString();
    }

    /**
     * Word-wraps {@code text} at {@code width}, splitting on existing
     * line breaks first. Words longer than {@code width} are hard-cut
     * so the box stays aligned.
     */
    private static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        for (String paragraph : text.split("\\R", -1)) {
            if (paragraph.isEmpty()) {
                out.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (word.isEmpty()) continue;
                if (word.length() > width) {
                    if (line.length() > 0) {
                        out.add(line.toString());
                        line.setLength(0);
                    }
                    int idx = 0;
                    while (idx < word.length()) {
                        int end = Math.min(idx + width, word.length());
                        out.add(word.substring(idx, end));
                        idx = end;
                    }
                    continue;
                }
                int needed = line.length() == 0 ? word.length() : line.length() + 1 + word.length();
                if (needed > width) {
                    out.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                } else {
                    if (line.length() > 0) line.append(' ');
                    line.append(word);
                }
            }
            if (line.length() > 0 || paragraph.isEmpty()) {
                out.add(line.toString());
            }
        }
        return out;
    }
}

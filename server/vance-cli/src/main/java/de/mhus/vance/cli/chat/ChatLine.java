package de.mhus.vance.cli.chat;

import com.consolemaster.AnsiColor;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * A single line in the chat history. Carries its semantic {@link Level} so the
 * view can pick a matching colour and prefix.
 */
public record ChatLine(Instant timestamp, Level level, String text) {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public enum Level {
        /** Informational note from the CLI itself (e.g. "connecting…"). */
        INFO(AnsiColor.BRIGHT_BLACK, "··"),
        /** System / lifecycle event (connection open, closed). */
        SYSTEM(AnsiColor.CYAN, "**"),
        /** Message we sent to the Brain. */
        SENT(AnsiColor.GREEN, "→"),
        /** Message received from the Brain. */
        RECEIVED(AnsiColor.BRIGHT_WHITE, "←"),
        /** User input echo. */
        USER(AnsiColor.YELLOW, ">"),
        /** Error condition. */
        ERROR(AnsiColor.BRIGHT_RED, "!!");

        private final AnsiColor color;
        private final String prefix;

        Level(AnsiColor color, String prefix) {
            this.color = color;
            this.prefix = prefix;
        }

        public AnsiColor color() {
            return color;
        }

        public String prefix() {
            return prefix;
        }
    }

    public static ChatLine of(Level level, String text) {
        return new ChatLine(Instant.now(), level, text);
    }

    public String formattedTimestamp() {
        return TIME.format(timestamp);
    }
}

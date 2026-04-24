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
        /**
         * Real chat content from the local user (role=USER from the server's
         * chat-message-appended notification). Always shown regardless of
         * verbosity — the chat is the point of the tool.
         */
        CHAT_USER(AnsiColor.BRIGHT_YELLOW, ">", 0),
        /** Chat reply from an assistant (role=ASSISTANT). Always shown. */
        CHAT_ASSISTANT(AnsiColor.BRIGHT_WHITE, "<", 0),
        /** In-chat system note (role=SYSTEM). Always shown. */
        CHAT_SYSTEM(AnsiColor.BRIGHT_CYAN, "~", 0),
        /** Error condition — always surfaced regardless of verbosity. */
        ERROR(AnsiColor.BRIGHT_RED, "!!", 0),
        /** Informational note from the CLI itself (e.g. "connecting…"). */
        INFO(AnsiColor.BRIGHT_BLACK, "··", 1),
        /** System / lifecycle event (connection open, closed). */
        SYSTEM(AnsiColor.CYAN, "**", 1),
        /** Typed, user-facing reply to a slash command. Always shown — the
         *  user asked for it, hiding it makes no sense. */
        RECEIVED(AnsiColor.WHITE, "←", 0),
        /** Message we sent to the Brain — wire-level trace. */
        SENT(AnsiColor.GREEN, "→", 2),
        /** Raw/unmapped inbound envelope — wire-level trace. */
        WIRE(AnsiColor.BRIGHT_BLACK, "~>", 2);

        private final AnsiColor color;
        private final String prefix;
        private final int minVerbosity;

        Level(AnsiColor color, String prefix, int minVerbosity) {
            this.color = color;
            this.prefix = prefix;
            this.minVerbosity = minVerbosity;
        }

        public AnsiColor color() {
            return color;
        }

        public String prefix() {
            return prefix;
        }

        /** Minimum verbosity level at which lines of this category are rendered. */
        public int minVerbosity() {
            return minVerbosity;
        }
    }

    public static ChatLine of(Level level, String text) {
        return new ChatLine(Instant.now(), level, text);
    }

    public String formattedTimestamp() {
        return TIME.format(timestamp);
    }
}

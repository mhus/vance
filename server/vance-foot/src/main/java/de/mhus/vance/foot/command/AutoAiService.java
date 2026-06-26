package de.mhus.vance.foot.command;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Per-process flag for the auto-AI mode — when on, every chat line
 * gets a {@code @ai } prefix prepended just before going onto the
 * wire so the agent reacts to every turn from this foot instance
 * (typical "pilot" role in a multi-user session). Starting a line
 * with {@code @no } escapes the prepend for that single turn.
 *
 * <p>In-memory, process-local: a foot restart resets the mode to
 * off. Toggle via the {@code /aa} slash command.
 *
 * <p>See {@code planning/multi-user-sessions.md} §6.
 */
@Component
public class AutoAiService {

    private static final Pattern NO_PREFIX = Pattern.compile("^@no(?:\\s+|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_MENTION = Pattern.compile("^@\\w");

    private volatile boolean on;

    public boolean isOn() {
        return on;
    }

    public void set(boolean value) {
        this.on = value;
    }

    public boolean toggle() {
        on = !on;
        return on;
    }

    /**
     * Rewrites a chat line for the wire: strips a leading {@code @no }
     * escape, otherwise prepends {@code @ai } when auto-AI is on and
     * the line does not already carry a mention.
     */
    public String apply(String line) {
        if (line == null) return "";
        String trimmed = line.stripLeading();
        if (NO_PREFIX.matcher(trimmed).find()) {
            return NO_PREFIX.matcher(trimmed).replaceFirst("").stripLeading();
        }
        if (!on) return line;
        if (ANY_MENTION.matcher(trimmed).find()) return line;
        return trimmed.isEmpty() ? "@ai" : "@ai " + line;
    }
}

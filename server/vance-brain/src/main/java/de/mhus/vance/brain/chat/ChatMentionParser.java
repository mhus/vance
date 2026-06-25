package de.mhus.vance.brain.chat;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Detects whether a user-typed chat turn addresses the agent
 * (mention syntax {@code @ai}, {@code @vance}, or any
 * {@code @<engine-name>}). See
 * {@code planning/multi-user-sessions.md} §2.2.
 *
 * <p>Used by the inbound WS-receive path (ProcessSteerHandler) to
 * route between two persistence modes in a multi-user session:
 *
 * <ul>
 *   <li><b>Addressed</b> — wake the agent's lane, persist with
 *       {@code addressedToAgent=true} (the existing pre-CollabMode
 *       path).</li>
 *   <li><b>Background</b> — only persist for chat history
 *       ({@code addressedToAgent=false}), do not append to the
 *       process's pending queue, do not wake the engine.</li>
 * </ul>
 *
 * <p>v1 implementation is intentionally simple — a plain regex with
 * word-boundary semantics. No code-block awareness, no escape
 * support; if a user writes {@code @ai} inside a Python snippet
 * they wake the agent. See plan §8 / §12 for the deferred-robustness
 * items.
 *
 * <p>{@code @here} and {@code @all} are deliberately <em>not</em>
 * agent mentions — they are human-to-human broadcast markers
 * (Slack convention). In CollabMode every USER message broadcasts
 * to other clients regardless of mention, so {@code @here}/{@code @all}
 * have no additional effect on the wire today; the parser still
 * lists them so the contract is documented.
 */
public final class ChatMentionParser {

    /**
     * Aliases that wake the agent. {@code @ai} is the quick-typing
     * form, {@code @vance} the product name, the engine names are
     * the explicit dispatch targets when several agents may coexist
     * in one session (future-proofing; v1 has one agent per session).
     */
    private static final Set<String> AGENT_ALIASES = Set.of(
            "ai",
            "vance",
            "arthur",
            "eddie",
            "ford",
            "hactar",
            "jeltz",
            "lunkwill",
            "marvin",
            "slartibartfast",
            "trillian",
            "vogon",
            "zaphod");

    /**
     * Capture one {@code @word} mention with word-boundary at both
     * ends. {@code (?<!\w)} prevents matches inside identifiers like
     * email addresses ({@code foo@bar} — the {@code o} before
     * {@code @} blocks). {@code (?!\w)} forbids continuations like
     * {@code @ailo}. Case-insensitive.
     */
    private static final Pattern MENTION = Pattern.compile(
            "(?<!\\w)@([A-Za-z][A-Za-z0-9_-]*)(?!\\w)",
            Pattern.CASE_INSENSITIVE);

    private ChatMentionParser() {}

    /**
     * Returns {@code true} when {@code text} contains at least one
     * agent mention. {@code null}/blank input → {@code false}.
     */
    public static boolean isAddressedToAgent(@Nullable String text) {
        if (text == null || text.isBlank()) return false;
        Matcher m = MENTION.matcher(text);
        while (m.find()) {
            String name = m.group(1).toLowerCase();
            if (AGENT_ALIASES.contains(name)) return true;
        }
        return false;
    }
}

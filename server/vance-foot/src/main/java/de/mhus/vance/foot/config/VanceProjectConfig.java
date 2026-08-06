package de.mhus.vance.foot.config;

import java.util.List;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/**
 * Root model for the project-local {@code .vancetope/config.yaml} file.
 * Loaded on startup (after {@code application.yaml}, before CLI flags) and
 * overlaid onto the running {@link FootConfig} by
 * {@link VanceProjectConfigApplier}.
 *
 * <p>This file is the home for per-project overrides that are <em>not</em>
 * credentials (those live in {@code project.eddie.yaml}). The first resident
 * is the {@link ConversationCapture} toggle + directory; future sections
 * (recipe presets, default profile, …) will be added here.
 */
@Data
public class VanceProjectConfig {

    private ConversationCapture conversationCapture = new ConversationCapture();
    private Defaults defaults = new Defaults();
    private @Nullable ToolPacks toolPacks;

    /**
     * Conversation audit logging — appends every chat message (USER and
     * ASSISTANT) as a JSON line to a per-session file, so the full
     * conversation is persisted on disk as it happens. Similar to
     * {@code .claude/exports/}, but written live instead of at session end.
     *
     * <p>Files land under {@code .vancetope/conversations/<YYYY>-<MM>/<sessionId>.jsonl}.
     * The year-month directory is derived from the wall-clock at write
     * time, so a session spanning midnight lands in two files — that's
     * intentional (keeps directories browsable by month).
     */
    @Data
    public static class ConversationCapture {
        /** Master switch. When {@code false}, no audit files are written. */
        private boolean enabled = false;
        /**
         * Base directory for audit files. Relative paths resolve against
         * the {@code .vancetope} directory (project-local or global home,
         * whichever is active). {@code null} or blank defaults to
         * {@code conversations} (i.e. {@code .vancetope/conversations/}).
         */
        private @Nullable String dir;
    }

    /**
     * Which tool packs are active in this project. Selection only — pack
     * <em>definitions</em> stay in {@code foot-tools/*.json} (global
     * {@code .vancetope} and/or project-local, merged with the project
     * winning on name). Keeping definition and selection apart means
     * this file can narrow what runs without becoming a second pack
     * format.
     *
     * <p>The whole block is nullable: an absent {@code toolPacks:} is not
     * "empty selection" but "don't steer", i.e. exactly today's
     * behaviour. Same for the two lists individually.
     *
     * <pre>
     * toolPacks:
     *   enabled: true          # false = no packs at all in this project
     *   packs: [chrome]        # allow-list; absent/empty = all of them
     *   disabledPacks: [jira]  # applied after packs
     * </pre>
     */
    @Data
    public static class ToolPacks {
        /** Master switch for this project. {@code null} = don't steer. */
        private @Nullable Boolean enabled;
        /** Allow-list of pack names. {@code null} or empty = no restriction. */
        private @Nullable List<String> packs;
        /** Deny-list of pack names, applied after {@link #packs}. */
        private @Nullable List<String> disabledPacks;
    }

    /**
     * Per-project default flags applied at startup, mirroring the
     * corresponding CLI flags. Each field is {@code null}/{@code false}
     * by default — only non-null/non-false values in the YAML override
     * the CLI defaults. CLI flags always win over these values
     * (precedence: {@code application.yaml < .vancetope/config.yaml < CLI}).
     */
    @Data
    public static class Defaults {
        /** Same as {@code --intellij-claude} — start the Claude IDE bridge. */
        private boolean intellijClaude = false;
        /** Same as {@code --intellij-mcp-default} — register the stock MCP endpoint. */
        private boolean intellijMcpDefault = false;
        /** Same as {@code --recipe <name>} — default session-chat recipe. */
        private @Nullable String recipe;
        /** Same as {@code --no-sandbox} — set to {@code false} to disable the file/exec sandbox. */
        private boolean sandbox = true;
    }
}

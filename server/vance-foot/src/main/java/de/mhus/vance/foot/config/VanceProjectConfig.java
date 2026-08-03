package de.mhus.vance.foot.config;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/**
 * Root model for the project-local {@code .vancetope/config.yaml} file.
 * Loaded on startup (after {@code application.yaml}, before CLI flags) and
 * overlaid onto the running {@link FootConfig} by
 * {@link VanceProjectConfigApplier}.
 *
 * <p>This file is the home for per-project overrides that are <em>not</em>
 * credentials (those live in {@code project.yaml}). The first resident
 * is the {@link ConversationAudit} toggle + directory; future sections
 * (recipe presets, default profile, …) will be added here.
 */
@Data
public class VanceProjectConfig {

    private ConversationAudit conversationAudit = new ConversationAudit();

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
    public static class ConversationAudit {
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
}

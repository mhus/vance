package de.mhus.vance.api.eddie;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * How Eddie's Channel-Adapter routes a worker's voice-relevant output
 * to the user's WS. Set per worker-link via the {@code process_observe}
 * tool. See {@code specification/eddie-engine.md} §7.
 *
 * <ul>
 *   <li>{@link #VERBATIM} — every frame 1:1 to the user (debug or
 *       explicit user wish).</li>
 *   <li>{@link #MILESTONES} — only status-transitions
 *       ({@code started}, {@code blocked}, {@code done}, {@code failed},
 *       gate-pass). Default for hub voice chat.</li>
 *   <li>{@link #SUMMARY} — milestones plus an LLM-summarizer pass on
 *       large outputs at terminal status.</li>
 *   <li>{@link #INBOX} — original goes to the user's inbox editor;
 *       Eddie only gets a mini-receipt in chat context.</li>
 * </ul>
 *
 * <p>Plan-frames ({@code todos-updated}, {@code plan-proposed},
 * {@code process-mode-changed}) bypass this channel and run through
 * the plan-mirror / fusion path independently of the chosen mode
 * (§7.3).
 */
@GenerateTypeScript("eddie")
public enum ChannelMode {
    VERBATIM,
    MILESTONES,
    SUMMARY,
    INBOX
}

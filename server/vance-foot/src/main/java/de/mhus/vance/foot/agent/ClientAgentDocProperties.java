package de.mhus.vance.foot.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.foot.agent.*} — tunables for the foot-side
 * {@code agent.md} / {@code CLAUDE.md} upload pipeline.
 *
 * <p>Two byte limits gate the upload:
 *
 * <ul>
 *   <li>{@link #warnBytes} — soft warning. The file is uploaded as-is
 *       but the UI shows a one-line warning next to the
 *       "agent doc: uploaded …" message. Use this to flag agent docs
 *       that are big enough to noticeably eat into the LLM context
 *       budget but not yet pathological.</li>
 *   <li>{@link #truncateBytes} — hard truncation. Anything beyond is
 *       cut, a trailing {@code "… [truncated]"} marker is appended, the
 *       truncated content is uploaded, and the UI shows a warning that
 *       the file was clipped. Replaces the previous "drop entirely"
 *       behaviour so the LLM still gets at least the head of the
 *       agent doc.</li>
 * </ul>
 *
 * <p>If {@code warnBytes &gt;= truncateBytes} the warn layer is
 * effectively superseded by truncation — set both intentionally.
 */
@Data
@ConfigurationProperties(prefix = "vance.foot.agent")
public class ClientAgentDocProperties {

    /**
     * Soft threshold in bytes. Files larger than this are still
     * uploaded, but the UI shows a warning next to the upload
     * confirmation. Default {@code 60_000} (~60 KB).
     */
    private int warnBytes = 60_000;

    /**
     * Hard threshold in bytes. Files larger than this are truncated to
     * this length (a trailing {@code "… [truncated]"} marker is added)
     * before upload, and the UI shows a warning. Default
     * {@code 100_000} (~100 KB).
     */
    private int truncateBytes = 100_000;
}

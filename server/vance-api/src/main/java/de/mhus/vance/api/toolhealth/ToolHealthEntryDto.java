package de.mhus.vance.api.toolhealth;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * One Tool-Health record as surfaced by the Insights API. Bundles the
 * persistent {@link ToolHealthDocument}-derived fields with the live
 * "is the cooldown still active right now?" snapshot the UI needs to
 * render a status + countdown for each tool.
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("toolhealth")
public class ToolHealthEntryDto {

    /** Mongo document id — opaque to the UI; useful for direct API ops. */
    @Nullable String id;

    /** Scope of the health record (PROJECT / SESSION / USER / TENANT / GLOBAL). */
    ToolHealthScope scope;

    /** Scope identifier (projectId / sessionId / userId / …). */
    String scopeId;

    /** Tool name (e.g. {@code zoho_imap__move_message}). */
    String toolName;

    /** Current status. */
    ToolHealthStatus status;

    /** Classification that produced the current status (may be older than the cooldowns). */
    @Nullable ToolHealthClassification classification;

    /** When the current status was first observed / last changed (ISO-8601 instant). */
    @Nullable String statusSince;

    /** Operator-readable estimate when the tool is expected to recover (ISO-8601 instant). */
    @Nullable String expectedRecoveryAt;

    /** Free-text note attached to the latest status change. */
    @Nullable String note;

    /** Only cooldowns whose {@code nextSpawnAllowedAt} is in the future. */
    List<ToolHealthCooldownDto> activeCooldowns;
}

package de.mhus.vance.api.megadodo;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One row of the project activity feed — see
 * {@code specification/public/megadodo-system.md}.
 *
 * <p>Two identifiers, and they answer different questions:
 *
 * <ul>
 *   <li>{@link #refType} + {@link #refId} name the <b>thing</b> the row is
 *       about. The UI turns them into a link.</li>
 *   <li>{@link #traceId} names the <b>operation</b>. All rows of one
 *       scheduler run share it, which is how the UI collapses a
 *       START/END pair into a single line.</li>
 * </ul>
 *
 * <p>For a scheduler they differ ({@code refId} = scheduler name,
 * {@code traceId} = one run); for a session's lifetime they coincide.
 * That is why it is two fields and not one.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("megadodo")
public class MegadodoEventDto {

    private String id;

    private Instant timestamp;

    /** Dotted, lowercase: {@code scheduler.run}, {@code session.lifecycle}. */
    private String action;

    private MegadodoPhase phase;

    private MegadodoSeverity severity;

    /** {@code success} | {@code failure} | {@code skipped} — only on END/SINGLE. */
    private @Nullable String outcome;

    /** Groups the rows of one operation. Never blank. */
    private String traceId;

    /** {@code null} for tenant-wide rows (user created, …). */
    private @Nullable String projectId;

    /** Who caused it. {@code null} means the system did. */
    private @Nullable String actor;

    private @Nullable MegadodoRefType refType;

    private @Nullable String refId;

    /**
     * Human-readable. On a failure this carries the cause a project owner
     * can act on — not "script execution failed".
     */
    private @Nullable String message;

    /**
     * Project-relative path of the detailed run log, when one exists.
     * The feed is the overview; this is the drill-down.
     */
    private @Nullable String logPath;

    /** Anything else worth showing, unstructured. */
    private @Nullable Map<String, Object> details;
}

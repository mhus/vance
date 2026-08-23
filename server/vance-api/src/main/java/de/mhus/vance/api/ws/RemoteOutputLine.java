package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One captured terminal line of a remote-controlled CLI client.
 *
 * <p>Plain text plus a severity {@code level} — deliberately not ANSI. The
 * watcher colours by level with its own theme; foot's line buffer is
 * line-oriented, so there is no byte stream to mirror in the first place (see
 * planning/foot-remote-control.md §1).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class RemoteOutputLine {

    /** Monotonic per-client sequence number. Gap detection + resume anchor. */
    private long seq;

    /** ISO-8601 instant the line was captured. */
    private @Nullable String timestamp;

    /** Verbosity level the line was emitted at ({@code INFO}, {@code WARN}, …). */
    private @Nullable String level;

    /** The line text, without trailing newline. */
    private String text;
}

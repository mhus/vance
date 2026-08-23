package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A batch of terminal lines from one CLI client to its watchers.
 *
 * <p>Produced only while at least one watcher is attached — nobody watching
 * means the producer pays nothing, same rule as the {@code signals} channel.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class RemoteOutputBatch {

    /** Which client produced these lines. */
    private String clientId;

    /** Lines in ascending {@code seq} order. */
    private List<RemoteOutputLine> lines;

    /**
     * True when the requested resume point had already fallen out of foot's
     * bounded line ring, so lines are missing before the first one here. Said
     * explicitly rather than presenting a gapless history that isn't one.
     */
    private boolean truncated;
}

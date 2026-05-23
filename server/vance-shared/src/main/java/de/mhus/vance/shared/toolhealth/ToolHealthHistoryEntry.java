package de.mhus.vance.shared.toolhealth;

import de.mhus.vance.api.toolhealth.ToolHealthClassification;
import de.mhus.vance.api.toolhealth.ToolHealthStatus;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Past status event on a tool-health document. Ring-buffer entry —
 * appended on every status transition or noteworthy diagnosis.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ToolHealthHistoryEntry {
    private Instant at = Instant.EPOCH;
    private @Nullable ToolHealthStatus status;
    private @Nullable ToolHealthClassification classification;
    private @Nullable String note;
    /** Identifier of the writer — system path, process id, or {@code "auto-clear"}. */
    private @Nullable String by;
    private @Nullable Instant expectedRecoveryAt;
}

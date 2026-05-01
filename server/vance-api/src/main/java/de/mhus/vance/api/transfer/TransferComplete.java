package de.mhus.vance.api.transfer;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Receiver → sender after the last chunk has been written and the
 * file is closed. {@code ok=true} signals the bytes are on disk and
 * the SHA-256 verification passed; {@code ok=false} (with
 * {@link #error}) means something went wrong mid-stream — the
 * receiver has already deleted the partial file.
 *
 * <p>Receivers may also emit this with {@code ok=false} <em>before</em>
 * the last chunk arrives if a write fails (disk full, IO error). The
 * sender stops chunking on the first {@code transfer-complete} for
 * the matching transferId.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("transfer")
public class TransferComplete {

    private String transferId;

    private boolean ok;

    private @Nullable String error;

    /** Total bytes written to the target file. */
    private long bytesWritten;

    /** {@code "ok"}, {@code "mismatch"} or {@code null} when no hash was given. */
    private @Nullable String hashCheck;
}

package de.mhus.vance.api.transfer;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * First frame of a transfer. Sender announces what it is about to
 * stream; receiver validates the destination, allocates the empty
 * target file, and replies with {@link TransferInitResponse}.
 *
 * <p>{@link #dirName} is set only when the receiver is the Brain — it
 * names the workspace RootDir into which {@link #target} resolves.
 * For the Foot side, {@link #target} is sandboxed against
 * {@code <footRoot>/<tenant>/<projectId>/}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("transfer")
public class TransferInit {

    private String transferId;

    /** Informative path on the sender side — for logging / audit. */
    private @Nullable String source;

    /** Path on the receiver side, relative to its sandbox root. */
    private String target;

    /** Brain RootDir name — only set when the receiver is the Brain. */
    private @Nullable String dirName;

    /** Exact byte count of the file to be transferred. */
    private long totalSize;

    /**
     * Hex-encoded SHA-256 of the file. Receiver verifies this against
     * the bytes it appended; mismatch leads to {@link TransferComplete}
     * with {@code ok=false}.
     */
    private String hash;

    /** Optional per-file metadata (mode, mtime). */
    private @Nullable TransferFileAttrs attrs;
}

package de.mhus.vance.api.transfer;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One chunk of file content. Sequence numbers are monotonically
 * increasing per transfer, starting at 0. {@link #last} is true only
 * on the final chunk; for an empty file the sender emits a single
 * chunk with {@code seq=0, bytes="", last=true}.
 *
 * <p>{@link #bytes} is Base64-encoded for v1 to keep the protocol
 * uniformly JSON. v2 may switch to binary WebSocket frames if the
 * 33% encoding overhead becomes an issue.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("transfer")
public class TransferChunk {

    private String transferId;

    private long seq;

    /** Base64-encoded chunk bytes. Empty string is valid (e.g. zero-byte files). */
    private String bytes;

    private boolean last;
}

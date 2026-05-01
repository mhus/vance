package de.mhus.vance.api.transfer;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Reply to {@link TransferInit}. {@code ok=true} means the receiver
 * has validated the path, checked disk-space, and allocated an empty
 * target file — the sender may now stream chunks. {@code ok=false}
 * aborts the transfer; {@code error} carries a short human-readable
 * reason for the LLM-facing tool result.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("transfer")
public class TransferInitResponse {

    private String transferId;

    private boolean ok;

    private @Nullable String error;
}

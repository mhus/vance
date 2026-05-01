package de.mhus.vance.api.transfer;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Sender → receiver as the closing frame of every transfer
 * lifecycle. Acknowledges the receiver's final status (whether
 * success or failure) and signals that both sides may drop the
 * {@code transferId} from their pending maps.
 *
 * <p>Also used in the special-case where a Foot upload fails before
 * the {@link TransferInit} can be sent (e.g. the local file does not
 * exist) — Foot replies to a {@code client-file-upload-request} with
 * {@link TransferFinish} carrying {@code ok=false}, skipping the
 * full lifecycle.
 *
 * <p>Receivers do not reply to {@code transfer-finish}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("transfer")
public class TransferFinish {

    private String transferId;

    private boolean ok;

    private @Nullable String error;
}

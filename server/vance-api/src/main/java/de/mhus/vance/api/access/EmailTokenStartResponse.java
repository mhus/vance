package de.mhus.vance.api.access;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Answer to {@code POST /brain/{tenant}/access/{username}/email-token}. Identical
 * whether or not the account exists, so it does not reveal that: the client
 * shows "a code was sent to the address on file" and sends
 * {@link #transactionId} back with the code.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("access")
public class EmailTokenStartResponse {

    /** Handle the verification must send back; the address is not unique, this is. */
    private String transactionId = "";

    /** When the code stops being valid, epoch milliseconds. */
    private long expiresAtTimestamp;
}

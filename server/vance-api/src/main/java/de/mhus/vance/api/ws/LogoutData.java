package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.Data;

/**
 * Payload of the {@link MessageType#LOGOUT} message — client-initiated session close.
 *
 * Currently carries no fields; reserved for future hints (e.g. a reason for logout).
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class LogoutData {
}

package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Payload of the {@link MessageType#ERROR} message — generic error response.
 *
 * {@link #getErrorCode()} follows HTTP semantics (400, 401, 403, 404, 409, 500).
 * {@link #getErrorMessage()} is a log-targeted text; clients should localize their
 * own user-facing copy based on the numeric code.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class ErrorData {

    private int errorCode;

    private @Nullable String errorMessage;
}

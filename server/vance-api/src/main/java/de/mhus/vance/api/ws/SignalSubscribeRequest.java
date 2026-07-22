package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Client → Brain payload on the {@code signals} channel for
 * {@link MessageType#SIGNAL_SUBSCRIBE} / {@link MessageType#SIGNAL_UNSUBSCRIBE}:
 * the document path to (un)subscribe to.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class SignalSubscribeRequest {

    /** Document path to (un)subscribe to. Required. */
    private String path;
}

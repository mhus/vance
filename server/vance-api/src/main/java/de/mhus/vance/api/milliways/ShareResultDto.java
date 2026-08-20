package de.mhus.vance.api.milliways;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Outcome of a completed share. {@link #message} is the one line the user
 * sees ("Shared with 3 users", "Sent to 2 recipients"); {@link #details}
 * carries the same facts in machine form — it is what the audit entry
 * records, not UI copy.
 *
 * <p>A partial result is a success with a qualifying {@link #message}: when
 * sharing with five people, one unreachable recipient must not cancel the
 * other four.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("milliways")
public class ShareResultDto {

    private String handlerId;

    private String message;

    @Builder.Default
    private Map<String, Object> details = new LinkedHashMap<>();
}

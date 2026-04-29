package de.mhus.vance.api.progress;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Free-form status ping for tool boundaries, web-search queries, file IO
 * and other engine asides. Soft 120-character recommendation on
 * {@link #text} — never enforced server-side; clients may abbreviate or
 * line-wrap as they see fit.
 *
 * <p>Pure side-channel: status pings do not enter conversation history,
 * are not persisted, and do not flow back into the LLM context on the
 * next round-trip.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("progress")
public class StatusPayload {

    private StatusTag tag;

    private String text;

    private @Nullable String detail;
}

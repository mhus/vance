package de.mhus.vance.api.inbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Filter parameters for {@code inbox-list}. {@code null} fields mean
 * "no filter" — default returns all PENDING items for the bound
 * user, sorted newest-first.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("inbox")
public class InboxListRequest {

    /** {@code null} → only PENDING. Use {@code "ALL"} for everything
     *  including ARCHIVED. */
    private @Nullable InboxItemStatus status;

    /** Filter on tags (any-of semantics). */
    private @Nullable List<String> tags;

    /** Filter to a specific session — defaults to the bound session
     *  if {@code null}. */
    private @Nullable String sessionId;

    /** Cap result size; default 100. */
    private @Nullable Integer limit;
}

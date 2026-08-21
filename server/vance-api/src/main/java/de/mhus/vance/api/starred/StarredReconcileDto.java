package de.mhus.vance.api.starred;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a reconcile: what every entry's target looks like right now.
 *
 * <p>This is the only place the N target lookups happen — the list endpoint
 * deliberately does not resolve, because that would put a fan-out over N
 * documents in N projects on the landing page. Reconcile is the named action
 * that pays that cost on request.
 *
 * <p>Nothing is deleted. Entries reported as {@code missing} or {@code forbidden}
 * stay in the file; removing a curation the user made is their call.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("starred")
public class StarredReconcileDto {

    /** One line per entry, in file order. */
    @Builder.Default
    private List<StarredReconcileEntryDto> entries = new ArrayList<>();

    /** Whether the control file was rewritten (some entry's kind/type drifted). */
    private boolean changed;
}

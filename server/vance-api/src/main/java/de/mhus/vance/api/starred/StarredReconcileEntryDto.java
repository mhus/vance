package de.mhus.vance.api.starred;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One line of a reconcile report.
 *
 * <p>{@code outcome} is one of {@code ok} / {@code refreshed} / {@code missing} /
 * {@code forbidden}. The last two are what the UI turns into a "remove" offer —
 * the server does not act on them by itself.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("starred")
public class StarredReconcileEntryDto {

    private String project;

    private String path;

    /** {@code ok} | {@code refreshed} | {@code missing} | {@code forbidden}. */
    private String outcome;

    private String message;
}

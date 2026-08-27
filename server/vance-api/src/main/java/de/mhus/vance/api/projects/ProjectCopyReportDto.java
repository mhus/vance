package de.mhus.vance.api.projects;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * What a project copy actually did.
 *
 * <p>A copy is a selection, not a sweep — so the report has to say what it
 * left out as loudly as what it carried. A bare success message would let the
 * operator believe the new project is the old one, and the two places that
 * belief breaks (missing credentials, missing permissions) are exactly the two
 * that are silent until someone runs into them.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("projects")
public class ProjectCopyReportDto {

    /** Name of the project that was copied. */
    private String sourceName;

    /** The project that now exists, as {@code GET /projects} would list it. */
    private @Nullable ProjectDto project;

    private int documentsCopied;

    /** Documents deliberately not carried — trash, logs, mounted entries. */
    private int documentsExcluded;

    private int documentsFailed;

    private int settingsCopied;

    /** Encrypted settings carried over — always {@code 0} without opt-in. */
    private int secretsCopied;

    /**
     * Keys of the encrypted settings that were <em>not</em> carried. Named
     * rather than counted: this is the list of things somebody has to set by
     * hand before the copy works.
     */
    @Builder.Default
    private List<String> secretsSkipped = new ArrayList<>();

    /** One line per document that could not be copied, with the reason. */
    @Builder.Default
    private List<String> failures = new ArrayList<>();

    /**
     * Entities that a copy never carries — sessions, chat, inbox threads,
     * permission grants, the workspace. Spelled out because the alternative
     * is an absent line, and an absent line reads as "there was nothing".
     */
    @Builder.Default
    private List<String> notCopied = new ArrayList<>();

    /** What happened to the copy's status, e.g. why it is suspended. */
    private @Nullable String statusNote;
}

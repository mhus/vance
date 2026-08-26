package de.mhus.vance.api.projects;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/admin/projects/{name}/placement} — what
 * the project requires of a pod.
 *
 * <p>Both fields are optional and mean "leave unchanged" when absent, so a
 * caller can revise the selector without restating the score. A present
 * {@code placementSelector} replaces the stored map wholesale: something
 * reconciling a desired state has to be able to remove a requirement, and merge
 * semantics would need a second endpoint for that.
 *
 * <p>An empty selector means "any pod", which is the state of every project
 * that predates the field. Keys must match {@code [A-Za-z0-9_-]{1,64}} — a dot
 * is a Mongo path separator, so it is rejected rather than rewritten.
 *
 * <p>Takes effect at the next placement; a running project is not moved. See
 * {@code planning/project-placement-labels.md} §2.4.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("projects")
public class ProjectPlacementRequest {

    private @Nullable Map<String, String> placementSelector;

    private @Nullable Integer homeResourceScore;
}

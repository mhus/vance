package de.mhus.vance.api.projects;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /brain/{tenant}/admin/projects/{name}/lifecycle-type}.
 *
 * <p>One of {@code AUTO} (default — let the derived {@code ownerRequired}
 * decide whether the project is kept on a live pod), {@code EPHEMERAL} (never
 * bring it up by itself) or {@code PERMANENT} (always keep it placed).
 * {@code HOMELESS} belongs to SYSTEM projects and is rejected here.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("projects")
public class ProjectLifecycleTypeRequest {

    @NotBlank
    private String lifecycleType;
}

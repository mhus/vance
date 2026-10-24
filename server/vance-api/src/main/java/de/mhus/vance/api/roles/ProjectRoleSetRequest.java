package de.mhus.vance.api.roles;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body of {@code PUT /brain/{tenant}/admin/roles/projects/{project}}: make a user or team a member. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("roles")
public class ProjectRoleSetRequest {

    @NotNull
    private RoleSubjectType subjectType;

    @NotBlank
    private String subjectId;

    @NotNull
    private ProjectRole role;
}

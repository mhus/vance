package de.mhus.vance.api.projects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code PUT /brain/{tenant}/admin/projects/{name}}. {@code null}
 * fields mean "leave as is". {@code projectGroupId} can be set to an empty
 * string explicitly to remove the group assignment — {@code null} means "don't
 * touch the current value".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("projects")
public class ProjectUpdateRequest {

    private @Nullable String title;

    private @Nullable Boolean enabled;

    /**
     * Use empty string to clear the group assignment; use {@code null} (i.e.
     * omit the field) to leave it untouched. The boolean below disambiguates
     * the two cases for serializers that drop empty strings.
     */
    private @Nullable String projectGroupId;

    @JsonProperty("clearProjectGroup")
    private boolean clearProjectGroup;

    private @Nullable List<String> teamIds;
}

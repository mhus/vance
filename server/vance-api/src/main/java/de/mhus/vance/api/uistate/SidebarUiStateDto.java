package de.mhus.vance.api.uistate;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-user sidebar collapse state. Carries the technical names of
 * project groups that the user has currently collapsed in the
 * shared {@code ProjectListSidebar} component. Missing entries
 * mean expanded (the default).
 *
 * <p>Persisted as a JSON-array string under the setting key
 * {@code webui.sidebar.collapsedProjectGroups} on the per-user
 * {@code _user_<login>} project; the serialization boundary
 * lives entirely in {@code MeUiStateController}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("uistate")
public class SidebarUiStateDto {

    @Builder.Default
    private List<String> collapsedProjectGroups = new ArrayList<>();
}

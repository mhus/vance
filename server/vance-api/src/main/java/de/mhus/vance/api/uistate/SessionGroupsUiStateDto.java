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
 * Per-user collapse state for the session-group blocks in the chat/cortex
 * session pickers. Missing entries mean expanded (the default).
 *
 * <p>Keys are opaque, client-namespaced strings — the picker uses
 * {@code <projectId>::<groupName>} so equally-named groups in different
 * projects don't share collapse state (session-group names are only unique
 * per {@code (project, user)}). The server treats them as opaque strings.
 *
 * <p>Kept separate from {@link SidebarUiStateDto} on purpose: the project
 * sidebar and the session pickers are independent components that each PUT
 * only their own state, so a shared DTO would let one wipe the other's.
 * Persisted under {@code webui.sessionGroups.collapsed} on the per-user
 * {@code _user_<login>} project.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("uistate")
public class SessionGroupsUiStateDto {

    @Builder.Default
    private List<String> collapsedKeys = new ArrayList<>();
}

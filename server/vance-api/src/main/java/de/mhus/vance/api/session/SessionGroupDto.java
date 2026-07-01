package de.mhus.vance.api.session;

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
 * Wire view of a session group — a per-user, per-project grouping of
 * sessions that exists purely for UI organisation (no scope, no rights,
 * no cascade). See {@code planning/session-groups.md}.
 *
 * <p>{@code sessionIds} is plain membership: the render side keeps the
 * usual {@code pinned + lastActivityAt} order inside a group, so the list
 * order carried here is not significant. Groups themselves order by
 * {@code sortIndex}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("session")
public class SessionGroupDto {

    /** Business identifier — {@code SessionGroupDocument.name}. */
    private String name;

    private @Nullable String title;

    /** Manual order of the group within the project's group list. */
    private int sortIndex;

    /**
     * Session ids ({@code SessionDocument.sessionId}) that belong to this
     * group. Membership only — order is not significant.
     */
    @Builder.Default
    private List<String> sessionIds = new ArrayList<>();
}

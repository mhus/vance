package de.mhus.vance.api.backup;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body for {@code POST /brain/{tenant}/backup/projects}. POST because the
 * filter lists are easier to send in a body than in a query string
 * (and query strings land in access logs).
 *
 * <p>Both include and exclude use Ant-style globs (the same
 * {@code PathGlob} the deny-list uses). Excluding after including is the
 * common case: "all projects except the scratchpad".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("backup")
public class BackupProjectsRequest {

    /** Project name globs to include. {@code null} or empty = include all readable. */
    private @Nullable List<String> include;

    /** Project name globs to exclude. Applied after {@link #include}. */
    private @Nullable List<String> exclude;

    /**
     * Whether to include disabled projects. Default {@code false}: a
     * disabled project is one the user explicitly set aside, and a default
     * backup that pulls it in would surprise the user. The opt-in is
     * explicit ({@code includeDisabled: true} on its own, or the project's
     * exact name in {@link #include}).
     */
    private boolean includeDisabled;
}

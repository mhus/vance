package de.mhus.vance.api.projects;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Read-only tree projection of a project workspace, returned by the workspace
 * explorer endpoints. The root node represents the workspace itself and lists
 * RootDirs as direct children; deeper levels mirror the on-disk layout inside
 * each RootDir. {@code path} is the slash-separated path from the workspace
 * root, never escaping it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("projects")
public class WorkspaceTreeNodeDto {

    private String name;

    /** Slash-separated path relative to the workspace root. Empty for the root node. */
    private String path;

    private WorkspaceNodeType type;

    /** File size in bytes; {@code 0} for directories. */
    private long size;

    private @Nullable Instant lastModified;

    /**
     * Children of a directory node. {@code null} when the depth limit was
     * reached (caller can request a follow-up listing) or when the node is a
     * file. Empty list means an empty directory.
     */
    private @Nullable List<WorkspaceTreeNodeDto> children;
}

package de.mhus.vance.shared.workspace;

import java.nio.file.Path;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Per-project workspace metadata. Persisted as {@code workspace.json}
 * at the workspace root. {@link Path} is reconstructed at load time
 * and not part of the JSON shape.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Workspace {

    private String tenant = "";

    private String projectId = "";

    /** ISO-8601 instant of creation. */
    private @Nullable String createdAt;

    /** Absolute path to the project's workspace folder. Not serialized. */
    private transient @Nullable Path root;
}

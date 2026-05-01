package de.mhus.vance.shared.workspace;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Persistent JSON descriptor for a single RootDir. Lives next to the
 * RootDir folder as {@code <dirName>.json} and is the source of truth
 * for handler dispatch, recovery and cleanup.
 *
 * <p>Schema: {@code specification/workspace-management.md} §4.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceDescriptor {

    /** Tenant the owning project belongs to. */
    private String tenant = "";

    /** Owning project id (the {@code name} field, not the Mongo id). */
    private String projectId = "";

    /** Foldername within the workspace, unique. */
    private String dirName = "";

    /** Worker-supplied hint, not unique. May differ from {@link #dirName} after collision suffixing. */
    private @Nullable String label;

    /** Handler key — {@code temp}, {@code git}, ... */
    private String type = "";

    /** Process that created this RootDir; drives {@link #deleteOnCreatorClose}. */
    private String creatorProcessId = "";

    /** Engine name — audit only. */
    private @Nullable String creatorEngine;

    /** Owner session — for cross-references. */
    private @Nullable String sessionId;

    /** ISO-8601 instant of creation. Stored as text to keep the descriptor JSON portable. */
    private @Nullable String createdAt;

    /** When {@code true}, the RootDir is removed when the creator process closes. */
    private boolean deleteOnCreatorClose;

    /** Handler-specific payload (git: {@code repoUrl}/{@code branch}/{@code commit}; temp: empty). */
    private @Nullable Map<String, Object> metadata;
}

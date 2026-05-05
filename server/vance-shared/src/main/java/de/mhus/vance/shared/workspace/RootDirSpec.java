package de.mhus.vance.shared.workspace;

import java.util.Map;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/**
 * Input to {@link WorkspaceService#createRootDir(RootDirSpec)}. Service
 * adds {@code dirName}, {@code createdAt}, {@code tenant}, normalizes
 * the descriptor, and returns a {@link RootDirHandle}.
 */
@Getter
@Builder
@ToString
public class RootDirSpec {

    /** Tenant the RootDir's project belongs to. Required. */
    private final String tenantId;

    /** Project under which the RootDir is created. Required. */
    private final String projectId;

    /** Handler key — {@code temp}, {@code git}, ... Required. */
    private final String type;

    /** Process that creates this RootDir. Required. */
    private final String creatorProcessId;

    /** Engine name — audit only. */
    private final @Nullable String creatorEngine;

    /** Owner session — for cross-references. */
    private final @Nullable String sessionId;

    /** Worker-supplied hint for the {@code dirName}. Service falls back to a UUID on collision or absence. */
    private final @Nullable String labelHint;

    /** When {@code true}, RootDir is removed on {@link WorkspaceService#disposeByCreator}. */
    private final boolean deleteOnCreatorClose;

    /** Handler-specific payload, copied verbatim into {@link WorkspaceDescriptor#getMetadata()}. */
    private final @Nullable Map<String, Object> metadata;
}

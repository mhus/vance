package de.mhus.vance.shared.workspace;

import java.nio.file.Path;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * Handle to a single RootDir on disk. Returned by
 * {@link WorkspaceService#createRootDir(RootDirSpec)} and friends.
 *
 * <p>Handles are <em>not</em> refcounted. Sharing means passing the
 * handle to other workers — when the original creator's process ends
 * and {@code deleteOnCreatorClose} was set, the RootDir is removed
 * regardless of who else still holds the handle. V2 may add refcounts.
 */
@Getter
@Builder
@ToString
public class RootDirHandle {

    private final String projectId;

    private final String dirName;

    private final String type;

    /** Absolute path to the RootDir folder. */
    private final Path path;

    private final WorkspaceDescriptor descriptor;

    public String creatorProcessId() {
        return descriptor.getCreatorProcessId();
    }

    public boolean deleteOnCreatorClose() {
        return descriptor.isDeleteOnCreatorClose();
    }
}

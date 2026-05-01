package de.mhus.vance.shared.workspace;

/**
 * Type-specific behaviour for a RootDir. Implementations are Spring
 * beans, registered with the {@link WorkspaceService} by the
 * {@link #type()} key.
 *
 * <p>The service handles folder creation/deletion and descriptor I/O —
 * handlers only fill or restore <em>content</em>. Folder removal during
 * close happens after this method returns.
 */
public interface WorkspaceContentHandler {

    /** Handler key, matches {@link RootDirSpec#getType()}. */
    String type();

    /**
     * Called once when the RootDir is created. Folder and descriptor
     * already exist; the handler populates initial content (e.g. a
     * git clone for the GitHandler). Throws to abort creation.
     */
    void init(RootDirHandle handle, RootDirSpec spec);

    /**
     * Called during workspace suspend. Updates the descriptor with
     * whatever recovery needs (e.g. git suspend-branch + commit).
     * Must <em>not</em> remove the folder — that's the service.
     *
     * <p>For ephemeral handlers (Temp): no-op — the folder is removed,
     * content is gone.
     */
    void suspend(RootDirHandle handle);

    /**
     * Called during workspace recover. Folder and descriptor are
     * already in place; the handler reconstructs content (e.g. git
     * clone + checkout from {@code descriptor.metadata}).
     */
    void recover(RootDirHandle handle, WorkspaceDescriptor descriptor);

    /**
     * Called during {@link WorkspaceService#disposeRootDir} before the
     * service removes folder + descriptor. Handlers can perform final
     * actions here (e.g. commit-on-close for git, if configured).
     */
    void close(RootDirHandle handle);
}

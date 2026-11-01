package de.mhus.vance.exec;

/**
 * Type-specific behaviour for a RootDir. Implementations are Spring
 * beans, registered with the {@link RootDirStore} by the
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
     * Called during {@link RootDirStore#disposeRootDir} before the
     * service removes folder + descriptor. Handlers can perform final
     * actions here (e.g. commit-on-close for git, if configured).
     */
    void close(RootDirHandle handle);
}

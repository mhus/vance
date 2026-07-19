package de.mhus.vance.brain.damogran;

/**
 * Per-run file backend for compose import/export — the one place the
 * import/export target (WORK vs. remote) differs. Importers/exporters read a
 * source / write a sink target-independently and go through {@code ctx.fileIo()}
 * to land bytes in / pull bytes from the run's workspace, without knowing
 * whether that is a server RootDir ({@link WorkspaceFileIo}) or a remote host's
 * filesystem via the {@code file_*} tools ({@link RemoteFileIo}).
 */
public interface ComposeFileIo {

    /**
     * Whether this backend carries raw bytes faithfully. WORK (server FS) does;
     * the remote text tools do not (content rides a UTF-8 string), so binary
     * import/export against CLIENT/DAEMON is best-effort text only.
     */
    boolean binaryCapable();

    /** Write {@code bytes} at the workspace-relative {@code relativePath}. */
    void writeBytes(String relativePath, byte[] bytes);

    /** Read the workspace-relative {@code relativePath} (up to {@code maxBytes}). */
    byte[] readBytes(String relativePath, long maxBytes);
}

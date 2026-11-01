package de.mhus.vance.exec;

/**
 * Ephemeral RootDir — short-lived container for one-shot purposes such
 * as file transfers ({@code specification/file-transfer.md}). The folder
 * is removed on close, like {@link TempHandler}.
 *
 * <p>Distinct type from {@code temp} so callers can express intent and
 * keep the lazy temp-cache (§7.3) separate from explicit per-purpose
 * folders. A worker that wants a dedicated drop folder for a single
 * upload chooses {@code ephemeral}; ad-hoc tempfiles via
 * {@link RootDirStore#createTempFile} stay on {@code temp}.
 */
public class EphemeralHandler implements WorkspaceContentHandler {

    public static final String TYPE = "ephemeral";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void init(RootDirHandle handle, RootDirSpec spec) {
        // Folder already created by service; nothing else to do.
    }

    @Override
    public void close(RootDirHandle handle) {
        // Folder removal handled by service.
    }
}

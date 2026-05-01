package de.mhus.vance.shared.workspace;

import org.springframework.stereotype.Component;

/**
 * Ephemeral RootDir — short-lived container for one-shot purposes such
 * as file transfers ({@code specification/file-transfer.md}). Same
 * persistence semantics as {@link TempHandler}: content is not carried
 * across suspend/recover and the folder is removed on close.
 *
 * <p>Distinct type from {@code temp} so callers can express intent and
 * keep the lazy temp-cache (§7.3) separate from explicit per-purpose
 * folders. A worker that wants a dedicated drop folder for a single
 * upload chooses {@code ephemeral}; ad-hoc tempfiles via
 * {@link WorkspaceService#createTempFile} stay on {@code temp}.
 */
@Component
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
    public void suspend(RootDirHandle handle) {
        // No-op: ephemeral content does not survive suspend.
    }

    @Override
    public void recover(RootDirHandle handle, WorkspaceDescriptor descriptor) {
        // Folder already recreated by service; content is gone, by design.
    }

    @Override
    public void close(RootDirHandle handle) {
        // Folder removal handled by service.
    }
}

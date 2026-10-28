package de.mhus.vance.exec;

/**
 * Temp RootDir — content is not persisted across suspend/recover.
 * After a pod migration the folder comes back empty, by design: this
 * keeps "results live as Documents" honest. Workers that need to keep
 * something across a suspend must import it as a
 * {@code DocumentDocument} before the
 * suspend.
 */
public class TempHandler implements WorkspaceContentHandler {

    public static final String TYPE = "temp";

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
        // No-op: temp content is not persisted.
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

package de.mhus.vance.exec;

/**
 * Temp RootDir — scratch content that is gone when the RootDir is closed.
 * This keeps "results live as Documents" honest: workers that need to keep
 * something must import it as a {@code DocumentDocument} first.
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
    public void close(RootDirHandle handle) {
        // Folder removal handled by service.
    }
}

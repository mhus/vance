package de.mhus.vance.shared.workspace;

/**
 * Thrown when a read operation refuses to return a file because it exceeds
 * the configured size limit. Distinct from
 * {@link WorkspaceQuotaExceededException} — the file is intact on disk, the
 * caller just asked for more bytes than the API allows in one shot. Web-UI
 * layer maps this to {@code 413 Payload Too Large}.
 */
public class WorkspaceFileSizeExceededException extends WorkspaceException {

    private final long fileSize;
    private final long limit;

    public WorkspaceFileSizeExceededException(String message, long fileSize, long limit) {
        super(message);
        this.fileSize = fileSize;
        this.limit = limit;
    }

    public long getFileSize() {
        return fileSize;
    }

    public long getLimit() {
        return limit;
    }
}

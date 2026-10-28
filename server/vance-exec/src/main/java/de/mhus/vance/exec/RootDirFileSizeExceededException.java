package de.mhus.vance.exec;

/**
 * A read refused to return a file because it exceeds the caller's size limit.
 * The file is intact on disk; the caller asked for more bytes than allowed in one
 * shot (the web layer maps this to {@code 413 Payload Too Large}).
 */
public class RootDirFileSizeExceededException extends RootDirException {

    private final long fileSize;
    private final long limit;

    public RootDirFileSizeExceededException(String message, long fileSize, long limit) {
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

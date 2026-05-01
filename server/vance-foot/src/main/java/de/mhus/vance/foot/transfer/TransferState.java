package de.mhus.vance.foot.transfer;

import de.mhus.vance.api.transfer.TransferFileAttrs;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.security.MessageDigest;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/**
 * Per-transfer bookkeeping. One instance lives in
 * {@link FootTransferService}'s pending map for the duration of a
 * transfer, regardless of role. Either {@link #fileChannel} (receiver)
 * or {@link #sourcePath} (sender) is set, never both.
 */
@Getter
@Setter
class TransferState {

    enum Role { SENDER, RECEIVER }

    enum Phase {
        INIT_SENT,             // sender has sent TransferInit, awaiting response
        STREAMING,             // sender / receiver is exchanging chunks
        COMPLETE_SENT,         // receiver sent TransferComplete, awaiting Finish
        COMPLETE_RECEIVED,     // sender saw TransferComplete, about to send Finish
        DONE
    }

    private final String transferId;
    private final Role role;
    private final Path path;
    private final long totalSize;
    private final String expectedHash;
    private final MessageDigest digest;

    /** Receiver side: open channel for append. */
    private @Nullable FileChannel fileChannel;

    /** Sender side: source file path on local disk. */
    private @Nullable Path sourcePath;

    /** Optional attrs to apply on the receiver (mode, mtime). */
    private @Nullable TransferFileAttrs attrs;

    /** Brain target (only for upload sender). */
    private @Nullable String dirName;
    private @Nullable String remotePath;

    private long bytesProcessed;
    private long nextSeqExpected;     // receiver
    private long nextSeqToSend;       // sender
    private volatile Phase phase;
    private volatile long lastActivityNanos;
    private volatile boolean abortRequested;

    TransferState(String transferId,
                  Role role,
                  Path path,
                  long totalSize,
                  String expectedHash,
                  MessageDigest digest) {
        this.transferId = transferId;
        this.role = role;
        this.path = path;
        this.totalSize = totalSize;
        this.expectedHash = expectedHash;
        this.digest = digest;
        this.phase = Phase.INIT_SENT;
        this.lastActivityNanos = System.nanoTime();
    }

    void touch() {
        this.lastActivityNanos = System.nanoTime();
    }
}

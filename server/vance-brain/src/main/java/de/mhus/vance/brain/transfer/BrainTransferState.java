package de.mhus.vance.brain.transfer;

import de.mhus.vance.api.transfer.TransferFileAttrs;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;
import org.springframework.web.socket.WebSocketSession;

/**
 * Per-transfer bookkeeping on the brain. Mirrors
 * {@code de.mhus.vance.foot.transfer.TransferState} but carries the
 * {@link WebSocketSession} of the bound client so chunk-sending can
 * happen without a registry lookup per frame.
 */
@Getter
@Setter
class BrainTransferState {

    enum Role { SENDER, RECEIVER }

    enum Phase {
        INIT_SENT,                // sender awaits init-response
        AWAITING_FOOT_INIT,       // upload: brain awaits Foot's TransferInit
        STREAMING,
        COMPLETE_SENT,            // receiver awaits Finish
        COMPLETE_RECEIVED,
        DONE
    }

    private final String transferId;
    private final Role role;
    private final String sessionId;
    private final WebSocketSession wsSession;
    private final CompletableFuture<TransferResult> future;

    /** Sender side: source path on brain workspace. */
    private @Nullable Path sourcePath;

    /** Receiver side: target path on brain workspace. */
    private @Nullable Path targetPath;

    /** Receiver side: open append channel. */
    private @Nullable FileChannel fileChannel;

    private @Nullable MessageDigest digest;
    private @Nullable String expectedHash;
    private long totalSize;
    private long bytesProcessed;

    /** For uploads: brain pre-fills these so frame handlers can resolve at TransferInit time. */
    private @Nullable String dirName;
    private @Nullable String remotePath;
    private @Nullable String tenantId;
    private @Nullable String projectId;
    private @Nullable TransferFileAttrs pendingAttrs;

    private long nextSeqExpected;
    private long nextSeqToSend;
    private volatile Phase phase;
    private volatile long lastActivityNanos;
    private volatile boolean abortRequested;

    BrainTransferState(String transferId,
                       Role role,
                       String sessionId,
                       WebSocketSession wsSession,
                       CompletableFuture<TransferResult> future) {
        this.transferId = transferId;
        this.role = role;
        this.sessionId = sessionId;
        this.wsSession = wsSession;
        this.future = future;
        this.phase = Phase.INIT_SENT;
        this.lastActivityNanos = System.nanoTime();
    }

    void touch() {
        this.lastActivityNanos = System.nanoTime();
    }
}

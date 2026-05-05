package de.mhus.vance.brain.transfer;

import de.mhus.vance.api.transfer.ClientFileUploadRequest;
import de.mhus.vance.api.transfer.TransferChunk;
import de.mhus.vance.api.transfer.TransferComplete;
import de.mhus.vance.api.transfer.TransferFileAttrs;
import de.mhus.vance.api.transfer.TransferFinish;
import de.mhus.vance.api.transfer.TransferInit;
import de.mhus.vance.api.transfer.TransferInitResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.events.SessionConnectionRegistry;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

/**
 * Brain-side state machine for file transfers. Drives both directions:
 * downloads (Brain workspace → Foot disk) and uploads (Foot disk →
 * Brain workspace). Spec: {@code specification/file-transfer.md}.
 *
 * <p>The two LLM-facing entry points {@link #startDownload} and
 * {@link #startUpload} return a {@link CompletableFuture} that the
 * caller (the brain tool) awaits to build the LLM tool result.
 */
@Service
@Slf4j
public class BrainTransferService {

    private static final int CHUNK_SIZE = 64 * 1024;

    private final WorkspaceService workspace;
    private final WebSocketSender sender;
    private final SessionConnectionRegistry connections;
    private final BrainTransferProperties properties;
    private final Map<String, BrainTransferState> pending = new ConcurrentHashMap<>();
    private final ExecutorService streamExecutor =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "vance-brain-transfer");
                t.setDaemon(true);
                return t;
            });
    private final ScheduledExecutorService sweeper =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "vance-brain-transfer-sweeper");
                t.setDaemon(true);
                return t;
            });

    public BrainTransferService(WorkspaceService workspace,
                                WebSocketSender sender,
                                SessionConnectionRegistry connections,
                                BrainTransferProperties properties) {
        this.workspace = workspace;
        this.sender = sender;
        this.connections = connections;
        this.properties = properties;
    }

    @PostConstruct
    void start() {
        long interval = properties.getSweeperIntervalMs();
        sweeper.scheduleWithFixedDelay(this::sweepTimeouts,
                interval, interval, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        sweeper.shutdownNow();
        streamExecutor.shutdownNow();
        for (BrainTransferState state : pending.values()) {
            closeQuietly(state);
            state.getFuture().completeExceptionally(
                    new IllegalStateException("brain shutting down"));
        }
        pending.clear();
    }

    // ─── LLM tool entry points ───────────────────────────────────

    /** Brain → Foot push. Reads source from workspace, streams chunks. */
    public CompletableFuture<TransferResult> startDownload(
            String sessionId,
            String tenantId,
            String projectId,
            String dirName,
            String remotePath,
            String localPath,
            @Nullable TransferFileAttrs callerAttrs) {
        WebSocketSession ws = connections.find(sessionId).orElse(null);
        if (ws == null) {
            return CompletableFuture.completedFuture(TransferResult.fail(
                    "no active connection for session " + sessionId));
        }
        Path source;
        try {
            source = workspace.resolve(tenantId, projectId, dirName, remotePath);
        } catch (WorkspaceException e) {
            return CompletableFuture.completedFuture(TransferResult.fail(e.getMessage()));
        }
        if (!Files.exists(source) || !Files.isRegularFile(source)) {
            return CompletableFuture.completedFuture(TransferResult.fail(
                    "source not found: " + dirName + "/" + remotePath));
        }
        long size;
        String hash;
        try {
            size = Files.size(source);
            hash = sha256(source);
        } catch (IOException e) {
            return CompletableFuture.completedFuture(TransferResult.fail(
                    "stat/hash failed: " + e.getMessage()));
        }
        if (size > properties.getMaxDownloadSize()) {
            return CompletableFuture.completedFuture(TransferResult.fail(
                    "exceeds max download size " + properties.getMaxDownloadSize()));
        }
        TransferFileAttrs attrs = callerAttrs != null ? callerAttrs : deriveAttrs(source);
        String transferId = UUID.randomUUID().toString();
        CompletableFuture<TransferResult> future = new CompletableFuture<>();
        BrainTransferState state = new BrainTransferState(
                transferId, BrainTransferState.Role.SENDER, sessionId, ws, future);
        state.setSourcePath(source);
        state.setTotalSize(size);
        state.setExpectedHash(hash);
        state.setPendingAttrs(attrs);
        pending.put(transferId, state);

        TransferInit init = TransferInit.builder()
                .transferId(transferId)
                .source(dirName + "/" + remotePath)
                .target(localPath)
                .totalSize(size)
                .hash(hash)
                .attrs(attrs)
                .build();
        try {
            sender.sendNotification(ws, MessageType.TRANSFER_INIT, init);
        } catch (IOException e) {
            failAndCleanup(state, "send init failed: " + e.getMessage());
        }
        return future;
    }

    /** Brain triggers Foot upload, becomes receiver. */
    public CompletableFuture<TransferResult> startUpload(
            String sessionId,
            String tenantId,
            String projectId,
            String dirName,
            String remotePath,
            String localPath,
            @Nullable TransferFileAttrs callerAttrs) {
        WebSocketSession ws = connections.find(sessionId).orElse(null);
        if (ws == null) {
            return CompletableFuture.completedFuture(TransferResult.fail(
                    "no active connection for session " + sessionId));
        }
        // Validate target dirName exists; reject early so the LLM gets a
        // crisp error before we touch the wire.
        if (workspace.getRootDir(tenantId, projectId, dirName).isEmpty()) {
            return CompletableFuture.completedFuture(TransferResult.fail(
                    "unknown RootDir: " + dirName));
        }
        String transferId = UUID.randomUUID().toString();
        CompletableFuture<TransferResult> future = new CompletableFuture<>();
        BrainTransferState state = new BrainTransferState(
                transferId, BrainTransferState.Role.RECEIVER, sessionId, ws, future);
        state.setTenantId(tenantId);
        state.setProjectId(projectId);
        state.setDirName(dirName);
        state.setRemotePath(remotePath);
        state.setPendingAttrs(callerAttrs);
        state.setPhase(BrainTransferState.Phase.AWAITING_FOOT_INIT);
        pending.put(transferId, state);

        ClientFileUploadRequest req = ClientFileUploadRequest.builder()
                .transferId(transferId)
                .localPath(localPath)
                .dirName(dirName)
                .remotePath(remotePath)
                .attrs(callerAttrs)
                .build();
        try {
            sender.sendNotification(ws, MessageType.CLIENT_FILE_UPLOAD_REQUEST, req);
        } catch (IOException e) {
            failAndCleanup(state, "send upload-request failed: " + e.getMessage());
        }
        return future;
    }

    // ─── Inbound frame handlers (called from WsHandler beans) ────

    /** Foot → Brain: opens an inbound upload. */
    public void onTransferInit(WebSocketSession ws, TransferInit init) {
        String transferId = init.getTransferId();
        BrainTransferState state = pending.get(transferId);
        if (state == null) {
            log.warn("init for unknown transfer {}", transferId);
            return;
        }
        if (state.getRole() != BrainTransferState.Role.RECEIVER
                || state.getPhase() != BrainTransferState.Phase.AWAITING_FOOT_INIT) {
            log.warn("init in wrong phase for {}: role={} phase={}",
                    transferId, state.getRole(), state.getPhase());
            return;
        }
        if (init.getTotalSize() < 0
                || init.getTotalSize() > properties.getMaxUploadSize()) {
            sendInitResponse(ws, transferId, false,
                    "exceeds max upload size " + properties.getMaxUploadSize());
            failAndCleanup(state, "size limit exceeded");
            return;
        }
        Path target;
        try {
            target = workspace.resolve(state.getTenantId(), state.getProjectId(),
                    state.getDirName(), state.getRemotePath());
        } catch (WorkspaceException e) {
            sendInitResponse(ws, transferId, false, e.getMessage());
            failAndCleanup(state, e.getMessage());
            return;
        }
        FileChannel channel;
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            channel = FileChannel.open(target,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            sendInitResponse(ws, transferId, false, "allocate failed: " + e.getMessage());
            failAndCleanup(state, "allocate failed");
            return;
        }
        state.setTargetPath(target);
        state.setFileChannel(channel);
        state.setTotalSize(init.getTotalSize());
        state.setExpectedHash(init.getHash());
        state.setDigest(newDigest());
        if (init.getAttrs() != null) {
            state.setPendingAttrs(init.getAttrs());
        }
        state.setPhase(BrainTransferState.Phase.STREAMING);
        state.touch();
        sendInitResponse(ws, transferId, true, null);
    }

    /** Foot → Brain: response to a Brain-initiated TransferInit (download). */
    public void onTransferInitResponse(TransferInitResponse rsp) {
        String transferId = rsp.getTransferId();
        BrainTransferState state = pending.get(transferId);
        if (state == null) {
            log.warn("init-response for unknown transfer {}", transferId);
            return;
        }
        if (state.getRole() != BrainTransferState.Role.SENDER) {
            log.warn("init-response on receiver-side transfer {}", transferId);
            return;
        }
        if (!rsp.isOk()) {
            String error = rsp.getError() != null ? rsp.getError() : "init declined";
            sendFinish(state.getWsSession(), transferId, false, error);
            state.getFuture().complete(TransferResult.fail(error));
            cleanup(transferId);
            return;
        }
        state.setPhase(BrainTransferState.Phase.STREAMING);
        state.touch();
        streamExecutor.submit(() -> streamChunks(state));
    }

    public void onTransferChunk(TransferChunk chunk) {
        String transferId = chunk.getTransferId();
        BrainTransferState state = pending.get(transferId);
        if (state == null) {
            log.warn("chunk for unknown transfer {}", transferId);
            return;
        }
        if (state.getRole() != BrainTransferState.Role.RECEIVER) {
            log.warn("chunk on sender-side transfer {}", transferId);
            return;
        }
        if (chunk.getSeq() != state.getNextSeqExpected()) {
            abortReceive(state, "out-of-order chunk: expected "
                    + state.getNextSeqExpected() + ", got " + chunk.getSeq());
            return;
        }
        byte[] data = Base64.getDecoder().decode(chunk.getBytes());
        try {
            state.getFileChannel().write(ByteBuffer.wrap(data));
        } catch (IOException e) {
            abortReceive(state, "write failed: " + e.getMessage());
            return;
        }
        state.getDigest().update(data);
        state.setBytesProcessed(state.getBytesProcessed() + data.length);
        state.setNextSeqExpected(state.getNextSeqExpected() + 1);
        state.touch();
        if (chunk.isLast()) {
            finalizeReceive(state);
        }
    }

    public void onTransferComplete(TransferComplete complete) {
        String transferId = complete.getTransferId();
        BrainTransferState state = pending.get(transferId);
        if (state == null) {
            log.warn("complete for unknown transfer {}", transferId);
            return;
        }
        if (state.getRole() != BrainTransferState.Role.SENDER) {
            log.warn("complete on receiver-side transfer {}", transferId);
            return;
        }
        state.setPhase(BrainTransferState.Phase.COMPLETE_RECEIVED);
        state.touch();
        sendFinish(state.getWsSession(), transferId, complete.isOk(), complete.getError());
        if (complete.isOk()) {
            state.getFuture().complete(
                    TransferResult.ok(complete.getBytesWritten(), state.getExpectedHash()));
        } else {
            state.getFuture().complete(TransferResult.fail(
                    complete.getError() != null ? complete.getError() : "transfer failed"));
        }
        cleanup(transferId);
    }

    public void onTransferFinish(TransferFinish finish) {
        String transferId = finish.getTransferId();
        BrainTransferState state = pending.remove(transferId);
        if (state == null) {
            return;
        }
        closeQuietly(state);
        // For receiver-side: future was already resolved when we sent
        // TransferComplete. Finish just triggers cleanup. Defensive
        // fallback for the impossible case where a sender saw Finish
        // without first seeing Complete.
        if (!state.getFuture().isDone()) {
            String error = finish.getError() != null ? finish.getError() : "transfer aborted";
            state.getFuture().complete(
                    finish.isOk()
                            ? TransferResult.ok(state.getBytesProcessed(), state.getExpectedHash())
                            : TransferResult.fail(error));
        }
    }

    // ─── Internal helpers ────────────────────────────────────────

    private void streamChunks(BrainTransferState state) {
        Path source = state.getSourcePath();
        if (source == null) {
            failAndCleanup(state, "no source");
            return;
        }
        try (FileChannel ch = FileChannel.open(source, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(CHUNK_SIZE);
            long remaining = state.getTotalSize();
            long seq = 0;
            if (remaining == 0) {
                sendChunk(state, seq, new byte[0], true);
                return;
            }
            while (remaining > 0 && !state.isAbortRequested()) {
                buf.clear();
                int read = ch.read(buf);
                if (read <= 0) {
                    abortSend(state, "unexpected EOF on source");
                    return;
                }
                byte[] data = new byte[read];
                buf.flip();
                buf.get(data);
                remaining -= read;
                boolean isLast = remaining == 0;
                sendChunk(state, seq++, data, isLast);
                state.setBytesProcessed(state.getBytesProcessed() + read);
                state.touch();
            }
        } catch (IOException e) {
            abortSend(state, "read failed: " + e.getMessage());
        }
    }

    private void sendChunk(BrainTransferState state, long seq, byte[] data, boolean last) {
        TransferChunk frame = TransferChunk.builder()
                .transferId(state.getTransferId())
                .seq(seq)
                .bytes(Base64.getEncoder().encodeToString(data))
                .last(last)
                .build();
        try {
            sender.sendNotification(state.getWsSession(), MessageType.TRANSFER_CHUNK, frame);
        } catch (IOException e) {
            abortSend(state, "send chunk failed: " + e.getMessage());
        }
    }

    private void finalizeReceive(BrainTransferState state) {
        FileChannel channel = state.getFileChannel();
        try {
            if (channel != null) {
                channel.force(true);
                channel.close();
            }
            state.setFileChannel(null);
        } catch (IOException e) {
            log.warn("close failed for {}: {}", state.getTransferId(), e.getMessage());
        }
        String actualHash = toHex(state.getDigest().digest());
        boolean hashOk = state.getExpectedHash() == null
                || state.getExpectedHash().equalsIgnoreCase(actualHash);
        Path target = state.getTargetPath();
        if (!hashOk) {
            if (target != null) {
                try { Files.deleteIfExists(target); } catch (IOException ignored) {}
            }
            sendComplete(state.getWsSession(), state.getTransferId(),
                    false, "hash mismatch", state.getBytesProcessed(), "mismatch");
            state.getFuture().complete(TransferResult.fail("hash mismatch"));
            state.setPhase(BrainTransferState.Phase.COMPLETE_SENT);
            return;
        }
        if (target != null) {
            applyAttrs(target, state.getPendingAttrs(), properties.getBrainModeMask());
        }
        sendComplete(state.getWsSession(), state.getTransferId(),
                true, null, state.getBytesProcessed(), "ok");
        state.getFuture().complete(
                TransferResult.ok(state.getBytesProcessed(), actualHash));
        state.setPhase(BrainTransferState.Phase.COMPLETE_SENT);
    }

    private void abortReceive(BrainTransferState state, String error) {
        log.warn("receive {} aborted: {}", state.getTransferId(), error);
        FileChannel channel = state.getFileChannel();
        if (channel != null) {
            try { channel.close(); } catch (IOException ignored) {}
            state.setFileChannel(null);
        }
        Path target = state.getTargetPath();
        if (target != null) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) {}
        }
        sendComplete(state.getWsSession(), state.getTransferId(),
                false, error, state.getBytesProcessed(), null);
        state.getFuture().complete(TransferResult.fail(error));
        state.setPhase(BrainTransferState.Phase.COMPLETE_SENT);
    }

    private void abortSend(BrainTransferState state, String error) {
        log.warn("send {} aborted: {}", state.getTransferId(), error);
        state.setAbortRequested(true);
        sendFinish(state.getWsSession(), state.getTransferId(), false, error);
        state.getFuture().complete(TransferResult.fail(error));
        cleanup(state.getTransferId());
    }

    private void sendInitResponse(WebSocketSession ws, String transferId,
                                  boolean ok, @Nullable String error) {
        TransferInitResponse rsp = TransferInitResponse.builder()
                .transferId(transferId)
                .ok(ok)
                .error(error)
                .build();
        try {
            sender.sendNotification(ws, MessageType.TRANSFER_INIT_RESPONSE, rsp);
        } catch (IOException e) {
            log.warn("send init-response failed for {}: {}", transferId, e.getMessage());
        }
    }

    private void sendComplete(WebSocketSession ws, String transferId, boolean ok,
                              @Nullable String error, long bytesWritten,
                              @Nullable String hashCheck) {
        TransferComplete frame = TransferComplete.builder()
                .transferId(transferId)
                .ok(ok)
                .error(error)
                .bytesWritten(bytesWritten)
                .hashCheck(hashCheck)
                .build();
        try {
            sender.sendNotification(ws, MessageType.TRANSFER_COMPLETE, frame);
        } catch (IOException e) {
            log.warn("send complete failed for {}: {}", transferId, e.getMessage());
        }
    }

    private void sendFinish(WebSocketSession ws, String transferId, boolean ok,
                            @Nullable String error) {
        TransferFinish frame = TransferFinish.builder()
                .transferId(transferId)
                .ok(ok)
                .error(error)
                .build();
        try {
            sender.sendNotification(ws, MessageType.TRANSFER_FINISH, frame);
        } catch (IOException e) {
            log.warn("send finish failed for {}: {}", transferId, e.getMessage());
        }
    }

    private void failAndCleanup(BrainTransferState state, String error) {
        state.setAbortRequested(true);
        if (!state.getFuture().isDone()) {
            state.getFuture().complete(TransferResult.fail(error));
        }
        cleanup(state.getTransferId());
    }

    private void cleanup(String transferId) {
        BrainTransferState state = pending.remove(transferId);
        if (state != null) {
            closeQuietly(state);
        }
    }

    private static void closeQuietly(BrainTransferState state) {
        FileChannel ch = state.getFileChannel();
        if (ch != null) {
            try { ch.close(); } catch (IOException ignored) {}
            state.setFileChannel(null);
        }
    }

    private void sweepTimeouts() {
        long deadlineNanos = System.nanoTime()
                - properties.getPhaseTimeoutMs() * 1_000_000L;
        for (Map.Entry<String, BrainTransferState> entry : pending.entrySet()) {
            BrainTransferState state = entry.getValue();
            if (state.getLastActivityNanos() < deadlineNanos) {
                log.warn("transfer {} timed out in phase {}",
                        state.getTransferId(), state.getPhase());
                if (state.getRole() == BrainTransferState.Role.RECEIVER) {
                    abortReceive(state, "phase timeout");
                } else {
                    abortSend(state, "phase timeout");
                }
            }
        }
    }

    private TransferFileAttrs deriveAttrs(Path source) {
        try {
            FileTime mtime = Files.getLastModifiedTime(source);
            return TransferFileAttrs.builder()
                    .mtime(Instant.ofEpochMilli(mtime.toMillis()).toString())
                    .build();
        } catch (IOException e) {
            return TransferFileAttrs.builder().build();
        }
    }

    private static void applyAttrs(Path target, @Nullable TransferFileAttrs attrs, int modeMask) {
        if (attrs == null) return;
        if (attrs.getMtime() != null) {
            try {
                Files.setLastModifiedTime(target,
                        FileTime.from(Instant.parse(attrs.getMtime())));
            } catch (Exception ignored) {
                // mtime is informational
            }
        }
        if (attrs.getMode() != null) {
            try {
                int mode = Integer.parseInt(attrs.getMode(), 8) & modeMask;
                String posix = toPosixString(mode);
                Files.setPosixFilePermissions(target,
                        java.nio.file.attribute.PosixFilePermissions.fromString(posix));
            } catch (Exception ignored) {
                // mode is informational; non-POSIX FS or invalid value → skip
            }
        }
    }

    private static String toPosixString(int mode) {
        StringBuilder sb = new StringBuilder(9);
        int[] bits = {0400, 0200, 0100, 0040, 0020, 0010, 0004, 0002, 0001};
        char[] chars = {'r', 'w', 'x', 'r', 'w', 'x', 'r', 'w', 'x'};
        for (int i = 0; i < 9; i++) {
            sb.append((mode & bits[i]) != 0 ? chars[i] : '-');
        }
        return sb.toString();
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest md = newDigest();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(CHUNK_SIZE);
            while (ch.read(buf) > 0) {
                buf.flip();
                md.update(buf);
                buf.clear();
            }
        }
        return toHex(md.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

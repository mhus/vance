package de.mhus.vance.foot.transfer;

import de.mhus.vance.api.transfer.ClientFileUploadRequest;
import de.mhus.vance.api.transfer.TransferChunk;
import de.mhus.vance.api.transfer.TransferComplete;
import de.mhus.vance.api.transfer.TransferFileAttrs;
import de.mhus.vance.api.transfer.TransferFinish;
import de.mhus.vance.api.transfer.TransferInit;
import de.mhus.vance.api.transfer.TransferInitResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Foot-side state machine for the file-transfer protocol. See
 * {@code specification/file-transfer.md}.
 *
 * <p>Owns inbound + outbound transfer state, drives chunk streaming on
 * a dedicated executor so the WebSocket dispatch thread stays free,
 * and sweeps stale entries on a timer. One service instance handles
 * many concurrent transfers, addressed by {@code transferId}.
 */
@Service
@Slf4j
public class FootTransferService {

    private static final int CHUNK_SIZE = 64 * 1024;
    private static final long PHASE_TIMEOUT_MS = 30_000;
    private static final long SWEEPER_INTERVAL_MS = 5_000;

    private final FootWorkspaceService workspace;
    private final FootWorkspaceProperties properties;
    private final ConnectionService connection;
    private final SessionService sessions;
    private final Map<String, TransferState> pending = new ConcurrentHashMap<>();
    private final ExecutorService streamExecutor =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "vance-foot-transfer");
                t.setDaemon(true);
                return t;
            });
    private final ScheduledExecutorService sweeper =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "vance-foot-transfer-sweeper");
                t.setDaemon(true);
                return t;
            });

    public FootTransferService(FootWorkspaceService workspace,
                               FootWorkspaceProperties properties,
                               @Lazy ConnectionService connection,
                               SessionService sessions) {
        this.workspace = workspace;
        this.properties = properties;
        this.connection = connection;
        this.sessions = sessions;
    }

    @PostConstruct
    void start() {
        sweeper.scheduleWithFixedDelay(this::sweepTimeouts,
                SWEEPER_INTERVAL_MS, SWEEPER_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        sweeper.shutdownNow();
        streamExecutor.shutdownNow();
        for (TransferState state : pending.values()) {
            closeQuietly(state);
        }
        pending.clear();
    }

    // ─── Inbound handlers (called from MessageDispatcher beans) ──

    /** Brain → Foot: an upload trigger. Foot becomes sender. */
    public void onUploadRequest(ClientFileUploadRequest req) {
        String transferId = req.getTransferId();
        SessionService.BoundSession bound = sessions.current();
        if (bound == null) {
            sendFinish(transferId, false, "no bound session");
            return;
        }
        Path local;
        try {
            local = workspace.resolveForRead(bound.projectId(), req.getLocalPath());
        } catch (TransferPathException e) {
            log.warn("upload {} rejected: {}", transferId, e.getMessage());
            sendFinish(transferId, false, e.getMessage());
            return;
        }
        long size;
        try {
            size = Files.size(local);
        } catch (IOException e) {
            sendFinish(transferId, false, "stat failed: " + e.getMessage());
            return;
        }
        if (size > properties.getMaxUploadSize()) {
            sendFinish(transferId, false,
                    "file exceeds max upload size " + properties.getMaxUploadSize());
            return;
        }
        String hash;
        try {
            hash = sha256(local);
        } catch (IOException e) {
            sendFinish(transferId, false, "hash failed: " + e.getMessage());
            return;
        }
        TransferFileAttrs attrs = req.getAttrs() != null
                ? req.getAttrs()
                : deriveAttrs(local);
        MessageDigest digest = newDigest();
        TransferState state = new TransferState(
                transferId, TransferState.Role.SENDER, local, size, hash, digest);
        state.setSourcePath(local);
        state.setDirName(req.getDirName());
        state.setRemotePath(req.getRemotePath());
        state.setAttrs(attrs);
        pending.put(transferId, state);

        TransferInit init = TransferInit.builder()
                .transferId(transferId)
                .source(local.toString())
                .target(req.getRemotePath())
                .dirName(req.getDirName())
                .totalSize(size)
                .hash(hash)
                .attrs(attrs)
                .build();
        sendFrame(MessageType.TRANSFER_INIT, init);
    }

    /** Brain → Foot: opening a download. Foot becomes receiver. */
    public void onTransferInit(TransferInit init) {
        String transferId = init.getTransferId();
        SessionService.BoundSession bound = sessions.current();
        if (bound == null) {
            sendInitResponse(transferId, false, "no bound session");
            sendFinish(transferId, false, "no bound session");
            return;
        }
        if (init.getTotalSize() < 0) {
            sendInitResponse(transferId, false, "invalid totalSize");
            return;
        }
        if (init.getTotalSize() > properties.getMaxDownloadSize()) {
            sendInitResponse(transferId, false,
                    "exceeds max download size " + properties.getMaxDownloadSize());
            return;
        }
        Path target;
        try {
            target = workspace.resolveForWrite(bound.projectId(), init.getTarget());
        } catch (TransferPathException e) {
            sendInitResponse(transferId, false, e.getMessage());
            return;
        }
        FileChannel channel;
        try {
            channel = FileChannel.open(target,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            sendInitResponse(transferId, false, "allocate failed: " + e.getMessage());
            return;
        }
        TransferState state = new TransferState(
                transferId, TransferState.Role.RECEIVER, target,
                init.getTotalSize(), init.getHash(), newDigest());
        state.setFileChannel(channel);
        state.setAttrs(init.getAttrs());
        state.setPhase(TransferState.Phase.STREAMING);
        pending.put(transferId, state);
        sendInitResponse(transferId, true, null);
    }

    /** Brain → Foot: response to a Foot-as-sender TransferInit. */
    public void onTransferInitResponse(TransferInitResponse rsp) {
        String transferId = rsp.getTransferId();
        TransferState state = pending.get(transferId);
        if (state == null) {
            log.warn("init-response for unknown transfer {}", transferId);
            return;
        }
        if (state.getRole() != TransferState.Role.SENDER) {
            log.warn("init-response on receiver-side transfer {}", transferId);
            return;
        }
        if (!rsp.isOk()) {
            log.info("upload {} declined by brain: {}", transferId, rsp.getError());
            sendFinish(transferId, false, rsp.getError());
            cleanup(transferId);
            return;
        }
        state.setPhase(TransferState.Phase.STREAMING);
        state.touch();
        streamExecutor.submit(() -> streamChunks(state));
    }

    public void onTransferChunk(TransferChunk chunk) {
        String transferId = chunk.getTransferId();
        TransferState state = pending.get(transferId);
        if (state == null) {
            log.warn("chunk for unknown transfer {}", transferId);
            return;
        }
        if (state.getRole() != TransferState.Role.RECEIVER) {
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
        TransferState state = pending.get(transferId);
        if (state == null) {
            log.warn("complete for unknown transfer {}", transferId);
            return;
        }
        if (state.getRole() != TransferState.Role.SENDER) {
            log.warn("complete on receiver-side transfer {}", transferId);
            return;
        }
        state.setPhase(TransferState.Phase.COMPLETE_RECEIVED);
        state.touch();
        sendFinish(transferId, complete.isOk(), complete.getError());
        cleanup(transferId);
    }

    public void onTransferFinish(TransferFinish finish) {
        String transferId = finish.getTransferId();
        TransferState state = pending.remove(transferId);
        if (state == null) {
            return;
        }
        closeQuietly(state);
    }

    // ─── Internal helpers ────────────────────────────────────────

    private void streamChunks(TransferState state) {
        try (FileChannel ch = FileChannel.open(state.getSourcePath(), StandardOpenOption.READ)) {
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

    private void sendChunk(TransferState state, long seq, byte[] data, boolean last) {
        TransferChunk frame = TransferChunk.builder()
                .transferId(state.getTransferId())
                .seq(seq)
                .bytes(Base64.getEncoder().encodeToString(data))
                .last(last)
                .build();
        sendFrame(MessageType.TRANSFER_CHUNK, frame);
    }

    private void finalizeReceive(TransferState state) {
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
        if (!hashOk) {
            try {
                Files.deleteIfExists(state.getPath());
            } catch (IOException ignored) {
                // best effort
            }
            sendComplete(state.getTransferId(), false, "hash mismatch", state.getBytesProcessed(),
                    "mismatch");
            state.setPhase(TransferState.Phase.COMPLETE_SENT);
            return;
        }
        applyAttrs(state.getPath(), state.getAttrs(), properties.getModeMask());
        sendComplete(state.getTransferId(), true, null, state.getBytesProcessed(), "ok");
        state.setPhase(TransferState.Phase.COMPLETE_SENT);
    }

    private void abortReceive(TransferState state, String error) {
        log.warn("receive {} aborted: {}", state.getTransferId(), error);
        FileChannel channel = state.getFileChannel();
        if (channel != null) {
            try { channel.close(); } catch (IOException ignored) {}
            state.setFileChannel(null);
        }
        try {
            Files.deleteIfExists(state.getPath());
        } catch (IOException ignored) {}
        sendComplete(state.getTransferId(), false, error, state.getBytesProcessed(), null);
        state.setPhase(TransferState.Phase.COMPLETE_SENT);
    }

    private void abortSend(TransferState state, String error) {
        log.warn("send {} aborted: {}", state.getTransferId(), error);
        state.setAbortRequested(true);
        sendFinish(state.getTransferId(), false, error);
        cleanup(state.getTransferId());
    }

    private void sendInitResponse(String transferId, boolean ok, @Nullable String error) {
        TransferInitResponse rsp = TransferInitResponse.builder()
                .transferId(transferId)
                .ok(ok)
                .error(error)
                .build();
        sendFrame(MessageType.TRANSFER_INIT_RESPONSE, rsp);
    }

    private void sendComplete(String transferId, boolean ok, @Nullable String error,
                              long bytesWritten, @Nullable String hashCheck) {
        TransferComplete frame = TransferComplete.builder()
                .transferId(transferId)
                .ok(ok)
                .error(error)
                .bytesWritten(bytesWritten)
                .hashCheck(hashCheck)
                .build();
        sendFrame(MessageType.TRANSFER_COMPLETE, frame);
    }

    private void sendFinish(String transferId, boolean ok, @Nullable String error) {
        TransferFinish frame = TransferFinish.builder()
                .transferId(transferId)
                .ok(ok)
                .error(error)
                .build();
        sendFrame(MessageType.TRANSFER_FINISH, frame);
    }

    private void sendFrame(String type, Object data) {
        if (!connection.send(WebSocketEnvelope.notification(type, data))) {
            log.warn("send failed for {} — connection dropped", type);
        }
    }

    private void cleanup(String transferId) {
        TransferState state = pending.remove(transferId);
        if (state != null) {
            closeQuietly(state);
        }
    }

    private static void closeQuietly(TransferState state) {
        FileChannel ch = state.getFileChannel();
        if (ch != null) {
            try { ch.close(); } catch (IOException ignored) {}
            state.setFileChannel(null);
        }
    }

    private void sweepTimeouts() {
        long deadlineNanos = System.nanoTime() - PHASE_TIMEOUT_MS * 1_000_000L;
        for (Map.Entry<String, TransferState> entry : pending.entrySet()) {
            TransferState state = entry.getValue();
            if (state.getLastActivityNanos() < deadlineNanos) {
                log.warn("transfer {} timed out in phase {}",
                        state.getTransferId(), state.getPhase());
                if (state.getRole() == TransferState.Role.RECEIVER) {
                    abortReceive(state, "phase timeout");
                } else {
                    abortSend(state, "phase timeout");
                }
            }
        }
    }

    private TransferFileAttrs deriveAttrs(Path local) {
        try {
            FileTime mtime = Files.getLastModifiedTime(local);
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
                // mtime is informational; failure is non-fatal
            }
        }
        if (attrs.getMode() != null) {
            try {
                int mode = Integer.parseInt(attrs.getMode(), 8) & modeMask;
                String posix = toPosixString(mode);
                Files.setPosixFilePermissions(target,
                        java.nio.file.attribute.PosixFilePermissions.fromString(posix));
            } catch (Exception ignored) {
                // Mode is informational; non-POSIX FS or invalid value → skip
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

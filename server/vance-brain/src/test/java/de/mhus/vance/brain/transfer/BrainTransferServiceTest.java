package de.mhus.vance.brain.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.transfer.TransferChunk;
import de.mhus.vance.api.transfer.TransferComplete;
import de.mhus.vance.api.transfer.TransferFinish;
import de.mhus.vance.api.transfer.TransferInit;
import de.mhus.vance.api.transfer.TransferInitResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.events.SessionConnectionRegistry;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.WorkspaceDescriptor;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.WebSocketSession;

/**
 * State-machine tests for {@link BrainTransferService}. The Foot side
 * is simulated by capturing outbound frames via a mocked
 * {@link WebSocketSender} and feeding canned inbound frames directly
 * into the service's frame-handler methods.
 */
class BrainTransferServiceTest {

    @TempDir
    Path brainRoot;

    WorkspaceService workspace;
    WebSocketSender sender;
    SessionConnectionRegistry connections;
    BrainTransferProperties properties;
    BrainTransferService service;
    WebSocketSession wsSession;

    final ConcurrentLinkedQueue<CapturedFrame> captured = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setUp() throws Exception {
        workspace = mock(WorkspaceService.class);
        sender = mock(WebSocketSender.class);
        connections = mock(SessionConnectionRegistry.class);
        properties = new BrainTransferProperties();
        wsSession = mock(WebSocketSession.class);

        when(connections.find(eq("s1"))).thenReturn(Optional.of(wsSession));

        // Capture every outbound frame so tests can inspect or react.
        doAnswer(inv -> {
            String type = inv.getArgument(1);
            Object data = inv.getArgument(2);
            captured.add(new CapturedFrame(type, data));
            return null;
        }).when(sender).sendNotification(any(), anyString(), any());

        service = new BrainTransferService(workspace, sender, connections, properties);
        service.start();
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void downloadHappyPath() throws Exception {
        Path source = brainRoot.resolve("source.bin");
        byte[] payload = "hello-world-this-is-a-payload".getBytes();
        Files.write(source, payload);
        when(workspace.resolve("t1", "p1", "outbox", "out.bin")).thenReturn(source);

        var future = service.startDownload(
                "s1", "t1", "p1", "outbox", "out.bin", "client/local.bin", null);

        // First frame: TransferInit with totalSize + hash
        TransferInit init = waitForFrame(MessageType.TRANSFER_INIT, TransferInit.class);
        assertThat(init.getTotalSize()).isEqualTo(payload.length);
        assertThat(init.getHash()).isEqualTo(sha256Hex(payload));
        String transferId = init.getTransferId();

        // Foot replies ok=true
        service.onTransferInitResponse(TransferInitResponse.builder()
                .transferId(transferId).ok(true).build());

        // Service streams chunks asynchronously — wait until 'last' chunk
        TransferChunk lastChunk = waitForLastChunk(transferId);
        assertThat(lastChunk.isLast()).isTrue();

        // Reassemble and verify the bytes that were sent
        byte[] reassembled = reassemble(transferId);
        assertThat(reassembled).isEqualTo(payload);

        // Foot sends Complete
        service.onTransferComplete(TransferComplete.builder()
                .transferId(transferId).ok(true)
                .bytesWritten(payload.length).hashCheck("ok").build());

        // Brain sends Finish
        TransferFinish finish = waitForFrame(MessageType.TRANSFER_FINISH, TransferFinish.class);
        assertThat(finish.getTransferId()).isEqualTo(transferId);
        assertThat(finish.isOk()).isTrue();

        // Tool future resolves with success
        TransferResult result = future.get(2, TimeUnit.SECONDS);
        assertThat(result.ok()).isTrue();
        assertThat(result.bytesWritten()).isEqualTo(payload.length);
    }

    @Test
    void downloadInitDeclined() throws Exception {
        Path source = brainRoot.resolve("source.bin");
        Files.write(source, new byte[]{1, 2, 3});
        when(workspace.resolve("t1", "p1", "outbox", "out.bin")).thenReturn(source);

        var future = service.startDownload(
                "s1", "t1", "p1", "outbox", "out.bin", "client/local.bin", null);
        TransferInit init = waitForFrame(MessageType.TRANSFER_INIT, TransferInit.class);

        service.onTransferInitResponse(TransferInitResponse.builder()
                .transferId(init.getTransferId()).ok(false).error("path invalid").build());

        TransferResult result = future.get(2, TimeUnit.SECONDS);
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("path invalid");
    }

    @Test
    void uploadHappyPath() throws Exception {
        Path target = brainRoot.resolve("uploaded.bin");
        when(workspace.getRootDir("t1", "p1", "uploads")).thenReturn(Optional.of(stubHandle(brainRoot)));
        when(workspace.resolve("t1", "p1", "uploads", "u.bin")).thenReturn(target);

        var future = service.startUpload(
                "s1", "t1", "p1", "uploads", "u.bin", "client/source.bin", null);

        // Brain triggers foot via ClientFileUploadRequest
        waitForFrame(MessageType.CLIENT_FILE_UPLOAD_REQUEST, Object.class);

        // Foot answers with TransferInit
        byte[] payload = "uploaded-payload-content".getBytes();
        String transferId = lastTransferIdFromUploadRequest();
        service.onTransferInit(wsSession, TransferInit.builder()
                .transferId(transferId)
                .source("client/source.bin")
                .target("u.bin")
                .totalSize(payload.length)
                .hash(sha256Hex(payload))
                .build());

        TransferInitResponse rsp = waitForFrame(
                MessageType.TRANSFER_INIT_RESPONSE, TransferInitResponse.class);
        assertThat(rsp.isOk()).isTrue();

        // Foot sends single chunk with last=true
        service.onTransferChunk(TransferChunk.builder()
                .transferId(transferId)
                .seq(0)
                .bytes(Base64.getEncoder().encodeToString(payload))
                .last(true).build());

        // Brain sends Complete
        TransferComplete complete = waitForFrame(MessageType.TRANSFER_COMPLETE, TransferComplete.class);
        assertThat(complete.isOk()).isTrue();
        assertThat(complete.getBytesWritten()).isEqualTo(payload.length);

        // Tool future resolves on Complete (does not need Finish)
        TransferResult result = future.get(2, TimeUnit.SECONDS);
        assertThat(result.ok()).isTrue();

        // File on disk
        assertThat(Files.readAllBytes(target)).isEqualTo(payload);

        // Foot sends Finish — triggers cleanup
        service.onTransferFinish(TransferFinish.builder()
                .transferId(transferId).ok(true).build());
    }

    @Test
    void uploadHashMismatchDeletesPartial() throws Exception {
        Path target = brainRoot.resolve("bad.bin");
        when(workspace.getRootDir("t1", "p1", "uploads")).thenReturn(Optional.of(stubHandle(brainRoot)));
        when(workspace.resolve("t1", "p1", "uploads", "bad.bin")).thenReturn(target);

        var future = service.startUpload(
                "s1", "t1", "p1", "uploads", "bad.bin", "client/source.bin", null);
        waitForFrame(MessageType.CLIENT_FILE_UPLOAD_REQUEST, Object.class);
        String transferId = lastTransferIdFromUploadRequest();

        byte[] real = "real-bytes".getBytes();
        service.onTransferInit(wsSession, TransferInit.builder()
                .transferId(transferId)
                .source("client/source.bin")
                .target("bad.bin")
                .totalSize(real.length)
                .hash("0000000000000000000000000000000000000000000000000000000000000000")
                .build());
        waitForFrame(MessageType.TRANSFER_INIT_RESPONSE, TransferInitResponse.class);

        service.onTransferChunk(TransferChunk.builder()
                .transferId(transferId).seq(0)
                .bytes(Base64.getEncoder().encodeToString(real))
                .last(true).build());

        TransferComplete complete = waitForFrame(MessageType.TRANSFER_COMPLETE, TransferComplete.class);
        assertThat(complete.isOk()).isFalse();
        assertThat(complete.getHashCheck()).isEqualTo("mismatch");

        TransferResult result = future.get(2, TimeUnit.SECONDS);
        assertThat(result.ok()).isFalse();
        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    void downloadEmptyFileSendsSingleLastChunk() throws Exception {
        Path source = brainRoot.resolve("empty.bin");
        Files.createFile(source);
        when(workspace.resolve("t1", "p1", "outbox", "empty.bin")).thenReturn(source);

        var future = service.startDownload(
                "s1", "t1", "p1", "outbox", "empty.bin", "client/empty.bin", null);
        TransferInit init = waitForFrame(MessageType.TRANSFER_INIT, TransferInit.class);
        assertThat(init.getTotalSize()).isZero();

        service.onTransferInitResponse(TransferInitResponse.builder()
                .transferId(init.getTransferId()).ok(true).build());

        TransferChunk only = waitForLastChunk(init.getTransferId());
        assertThat(only.getSeq()).isZero();
        assertThat(only.isLast()).isTrue();
        assertThat(only.getBytes()).isEmpty();

        service.onTransferComplete(TransferComplete.builder()
                .transferId(init.getTransferId()).ok(true).hashCheck("ok").build());

        future.get(2, TimeUnit.SECONDS);
    }

    @Test
    void downloadMissingSourceFails() throws Exception {
        when(workspace.resolve("t1", "p1", "outbox", "missing.bin"))
                .thenReturn(brainRoot.resolve("missing.bin"));

        TransferResult result = service.startDownload(
                "s1", "t1", "p1", "outbox", "missing.bin", "client/x.bin", null)
                .get(2, TimeUnit.SECONDS);
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("not found");
    }

    @Test
    void uploadUnknownDirNameFails() throws Exception {
        when(workspace.getRootDir("t1", "p1", "ghost")).thenReturn(Optional.empty());

        TransferResult result = service.startUpload(
                "s1", "t1", "p1", "ghost", "x.bin", "client/x.bin", null)
                .get(2, TimeUnit.SECONDS);
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("unknown RootDir");
    }

    @Test
    void downloadWithoutConnectionFails() throws Exception {
        when(connections.find("s2")).thenReturn(Optional.empty());

        TransferResult result = service.startDownload(
                "s2", "t1", "p1", "outbox", "x.bin", "client/x.bin", null)
                .get(2, TimeUnit.SECONDS);
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("no active connection");
    }

    // ─── helpers ─────────────────────────────────────────────────

    private <T> T waitForFrame(String type, Class<T> dataType) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            for (CapturedFrame f : captured) {
                if (f.type.equals(type)) {
                    captured.remove(f);
                    return dataType.cast(f.data);
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for frame: " + type
                + " (have: " + captured.stream().map(f -> f.type).toList() + ")");
    }

    private TransferChunk waitForLastChunk(String transferId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            for (CapturedFrame f : captured) {
                if (f.type.equals(MessageType.TRANSFER_CHUNK)
                        && f.data instanceof TransferChunk c
                        && c.getTransferId().equals(transferId)
                        && c.isLast()) {
                    return c;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for last chunk of " + transferId);
    }

    private byte[] reassemble(String transferId) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (CapturedFrame f : captured) {
            if (f.type.equals(MessageType.TRANSFER_CHUNK)
                    && f.data instanceof TransferChunk c
                    && c.getTransferId().equals(transferId)) {
                try {
                    out.write(Base64.getDecoder().decode(c.getBytes()));
                } catch (java.io.IOException ignored) {}
            }
        }
        return out.toByteArray();
    }

    private String lastTransferIdFromUploadRequest() throws Exception {
        // Easier than inspecting the captured object: peek the most recent
        // request the service has registered. ID was generated by the
        // service on startUpload.
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(sender, org.mockito.Mockito.atLeastOnce())
                .sendNotification(any(), eq(MessageType.CLIENT_FILE_UPLOAD_REQUEST), captor.capture());
        List<Object> reqs = captor.getAllValues();
        Object last = reqs.get(reqs.size() - 1);
        return ((de.mhus.vance.api.transfer.ClientFileUploadRequest) last).getTransferId();
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(bytes);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static RootDirHandle stubHandle(Path path) {
        return RootDirHandle.builder()
                .projectId("p1")
                .dirName("uploads")
                .type("ephemeral")
                .path(path)
                .descriptor(WorkspaceDescriptor.builder()
                        .creatorProcessId("p")
                        .deleteOnCreatorClose(true)
                        .build())
                .build();
    }

    private record CapturedFrame(String type, Object data) {}
}

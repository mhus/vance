package de.mhus.vance.foot.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.transfer.TransferChunk;
import de.mhus.vance.api.transfer.TransferInit;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * State-machine coverage for {@link FootTransferService}'s RECEIVER path:
 * happy download, out-of-order / oversize / malformed-base64 aborts,
 * size + hash verification, and — the regression this pins — the sweeper
 * must NOT delete a completed, hash-verified file when TRANSFER_FINISH is
 * delayed past the phase timeout.
 */
class FootTransferServiceTest {

    @TempDir
    Path root;

    private FootWorkspaceProperties properties;
    private FootWorkspaceService workspace;
    private FootTransferService service;

    @BeforeEach
    void setUp() {
        properties = new FootWorkspaceProperties();
        properties.setRoot(root.toString());
        FootConfig footConfig = new FootConfig();
        footConfig.getAuth().setTenant("acme");
        workspace = new FootWorkspaceService(properties, footConfig);

        ConnectionService connection = mock(ConnectionService.class);
        SessionService sessions = mock(SessionService.class);
        when(sessions.current())
                .thenReturn(new SessionService.BoundSession("s1", "p1", null, null));
        service = new FootTransferService(workspace, properties, connection, sessions);
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private static String sha256Hex(byte[] b) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder(h.length * 2);
        for (byte x : h) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private void init(String id, String target, long totalSize, String hash) {
        service.onTransferInit(TransferInit.builder()
                .transferId(id).target(target).totalSize(totalSize).hash(hash).build());
    }

    private void chunk(String id, long seq, byte[] bytes, boolean last) {
        service.onTransferChunk(TransferChunk.builder()
                .transferId(id).seq(seq)
                .bytes(Base64.getEncoder().encodeToString(bytes))
                .last(last).build());
    }

    private Path targetPath(String target) {
        return workspace.resolveForWrite("p1", target);
    }

    private void ageAllPastDeadline() {
        long old = System.nanoTime() - 60_000L * 1_000_000L; // 60s ago > 30s timeout
        service.pending.values().forEach(s -> s.setLastActivityNanos(old));
    }

    // ─── tests ───────────────────────────────────────────────────────────

    @Test
    void happyPath_writesFile_andReachesDonePhase() throws Exception {
        byte[] content = "hello vance".getBytes();
        init("t1", "dl/a.txt", content.length, sha256Hex(content));
        chunk("t1", 0, content, true);

        assertThat(Files.readAllBytes(targetPath("dl/a.txt"))).isEqualTo(content);
        assertThat(service.pending.get("t1").getPhase())
                .isEqualTo(TransferState.Phase.DONE);
    }

    @Test
    void outOfOrderChunk_abortsAndDeletesPartialFile() throws Exception {
        byte[] content = "abc".getBytes();
        init("t2", "dl/b.txt", content.length, sha256Hex(content));
        chunk("t2", 1, content, true); // expected seq 0

        assertThat(Files.exists(targetPath("dl/b.txt"))).isFalse();
        assertThat(service.pending.get("t2").getPhase())
                .isEqualTo(TransferState.Phase.COMPLETE_SENT);
    }

    @Test
    void streamExceedingDeclaredSize_aborts() {
        init("t3", "dl/c.txt", 3, "irrelevant");
        chunk("t3", 0, "abcdef".getBytes(), true); // 6 > 3

        assertThat(Files.exists(targetPath("dl/c.txt"))).isFalse();
    }

    @Test
    void malformedBase64_aborts() {
        init("t4", "dl/d.txt", 10, "irrelevant");
        service.onTransferChunk(TransferChunk.builder()
                .transferId("t4").seq(0).bytes("!!! not base64 !!!").last(true).build());

        assertThat(Files.exists(targetPath("dl/d.txt"))).isFalse();
    }

    @Test
    void sizeMismatch_onLastChunk_deletesFile() throws Exception {
        // Declares 10 bytes but the (only, last) chunk carries 3.
        init("t5", "dl/e.txt", 10, sha256Hex("abc".getBytes()));
        chunk("t5", 0, "abc".getBytes(), true);

        assertThat(Files.exists(targetPath("dl/e.txt"))).isFalse();
    }

    @Test
    void hashMismatch_deletesFile() {
        byte[] content = "abc".getBytes();
        init("t6", "dl/f.txt", content.length, "deadbeef"); // wrong hash
        chunk("t6", 0, content, true);

        assertThat(Files.exists(targetPath("dl/f.txt"))).isFalse();
    }

    @Test
    void sweeper_doesNotDeleteCompletedFile_whenFinishDelayed() throws Exception {
        // Regression (code-review-2): finalizeReceive left the state pending;
        // the sweeper reaped any receiver older than the timeout and deleted
        // the good file when TRANSFER_FINISH was delayed/lost.
        byte[] content = "verified".getBytes();
        init("t7", "dl/g.txt", content.length, sha256Hex(content));
        chunk("t7", 0, content, true);
        assertThat(Files.exists(targetPath("dl/g.txt"))).isTrue();

        ageAllPastDeadline();
        service.sweepTimeouts();

        // The completed, hash-verified file must survive; the stale
        // bookkeeping entry is evicted.
        assertThat(Files.readAllBytes(targetPath("dl/g.txt"))).isEqualTo(content);
        assertThat(service.pending).doesNotContainKey("t7");
    }

    @Test
    void sweeper_stillAbortsAndDeletesAStalledPartialTransfer() {
        // A receiver stuck mid-stream past the timeout is still cleaned up
        // (partial file removed) — the terminal-phase skip must not disable it.
        init("t8", "dl/h.txt", 100, "irrelevant");
        chunk("t8", 0, "partial".getBytes(), false); // still STREAMING, not last

        ageAllPastDeadline();
        service.sweepTimeouts();

        assertThat(Files.exists(targetPath("dl/h.txt"))).isFalse();
    }
}

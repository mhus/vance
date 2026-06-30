package de.mhus.vance.foot.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.LiveRegion;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class PermissionPromptTest {

    private static final String PATH = "/tmp/vance-test-prompt/file.txt";

    private final ChatTerminal terminal = mock(ChatTerminal.class);
    private final LiveRegion liveRegion = mock(LiveRegion.class);

    private record Stack(PermissionPrompt prompt,
                         PendingPermissionPrompt pending,
                         PermissionConfigLoader loader,
                         PermissionService permissions) {}

    private Stack stack(Path dir, boolean attached) {
        when(liveRegion.isAttached()).thenReturn(attached);
        PermissionConfigLoader loader =
                new PermissionConfigLoader(dir.resolve("permissions.yaml").toString(), "");
        PermissionService permissions = new PermissionService(loader);
        PendingPermissionPrompt pending = new PendingPermissionPrompt(terminal);
        PermissionPrompt prompt =
                new PermissionPrompt(pending, terminal, liveRegion, loader, permissions);
        return new Stack(prompt, pending, loader, permissions);
    }

    private void awaitActive(PendingPermissionPrompt pending) {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!pending.hasActive()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("prompt never became active");
            }
            Thread.onSpinWait();
        }
    }

    @Test
    void resolve_headless_deniesWithoutPrompting(@TempDir Path dir) {
        Stack s = stack(dir, false);

        PermissionDecision decision =
                s.prompt().resolve("client_file_read", PermissionDomain.PATHS, PATH);

        assertThat(decision).isEqualTo(PermissionDecision.DENY);
        assertThat(s.pending().hasActive()).isFalse();
    }

    @Test
    void resolve_allowAlways_persistsRule_andReloadsPolicy(@TempDir Path dir) {
        Stack s = stack(dir, true);

        CompletableFuture<PermissionDecision> result = CompletableFuture.supplyAsync(
                () -> s.prompt().resolve("client_file_write", PermissionDomain.PATHS, PATH));
        awaitActive(s.pending());
        s.pending().offerAnswer("2"); // allow always

        assertThat(result.join()).isEqualTo(PermissionDecision.ALLOW);
        assertThat(s.loader().load().getPaths().getAllow()).contains(PATH);
        assertThat(s.permissions().policy().evaluatePath(PermissionPaths.canonicalize(PATH)))
                .isEqualTo(PermissionDecision.ALLOW);
    }

    @Test
    void resolve_denyAlways_persistsDenyRule(@TempDir Path dir) {
        Stack s = stack(dir, true);

        CompletableFuture<PermissionDecision> result = CompletableFuture.supplyAsync(
                () -> s.prompt().resolve("client_exec_run", PermissionDomain.COMMANDS, "make evil"));
        awaitActive(s.pending());
        s.pending().offerAnswer("4"); // deny always

        assertThat(result.join()).isEqualTo(PermissionDecision.DENY);
        assertThat(s.loader().load().getCommands().getDeny())
                .anyMatch(r -> r.contains("make evil"));
    }

    @Test
    void resolve_allowOnce_doesNotPersist(@TempDir Path dir) {
        Stack s = stack(dir, true);

        CompletableFuture<PermissionDecision> result = CompletableFuture.supplyAsync(
                () -> s.prompt().resolve("client_file_read", PermissionDomain.PATHS, PATH));
        awaitActive(s.pending());
        s.pending().offerAnswer("1"); // allow once

        assertThat(result.join()).isEqualTo(PermissionDecision.ALLOW);
        assertThat(s.loader().load().getPaths().getAllow()).isEmpty();
    }
}
